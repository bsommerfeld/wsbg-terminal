package de.bsommerfeld.wsbg.terminal.agent;
import de.bsommerfeld.wsbg.terminal.embedding.EmbeddingService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.HeadlineWriter.Draft;
import de.bsommerfeld.wsbg.terminal.agent.HeadlineWriter.DraftSubject;
import de.bsommerfeld.wsbg.terminal.agent.TickerResolver.ResolvedSubject;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.i18n.I18nService;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns the dirty clusters of one editorial tick into published headlines via a
 * fixed, deterministic pipeline — no free tool loop, no round cap.
 *
 * <p>Per cluster, two model calls with deterministic glue between them:
 * <ol>
 *   <li><b>Subject extraction</b> (model): the cluster brief in, a list of
 *       market-relevant subject names out (slang normalised to canonical/English
 *       via {@link WsbgJargon}).</li>
 *   <li><b>Resolve</b> (code, {@link TickerResolver}): each subject → validated
 *       Yahoo ticker + live market data + news, or news-only, or nothing.</li>
 *   <li><b>Headline composition</b> (model): the brief + the resolved data in,
 *       structured headline drafts out.</li>
 *   <li><b>Write</b> (code, {@link HeadlineWriter}): QA + persist + broadcast.</li>
 * </ol>
 *
 * <p>This replaces the former agentic tool-use loop (getCluster / lookupTicker /
 * publishHeadline / done). The editorial policy is "translate, almost always
 * publish", which is a per-cluster transform, not an investigation — so the
 * loop machinery was pure overhead.
 */
@Singleton
public class EditorialAgent {

    private static final Logger LOG = LoggerFactory.getLogger(EditorialAgent.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Subjects are NOT capped — the wire mirrors the room 1:1, so every named
     * subject gets its own focused compose call. The expensive second-hop
     * (related instruments) is instead a shared budget spread evenly across ALL
     * subjects ({@link #distributeRelated}, round-robin), so a 22-subject cluster
     * gives each subject ~1 related instead of loading the first 6 with 4 each.
     * {@code RELATED_BUDGET} = total related lookups per cluster (= the old
     * 6×4); {@code RELATED_PER_SUBJECT} = cap any single subject can take.
     */
    private static final int RELATED_BUDGET = 24;
    private static final int RELATED_PER_SUBJECT = 4;

    /**
     * When a cluster carries more than this many comments, subject extraction is
     * run in batches of this size and the names unioned; no comment is dropped
     * (every batch is extracted, names deduped). This is a num_ctx overflow guard
     * for monster threads now, NOT a degeneration guard: the old tight thresholds
     * (25 comments / 2 800 chars) dated from the hidden-thinking era, where the
     * invisible reasoning ate the token budget and long briefs came back
     * blank/truncated. With think=false the model enumerates a 48-name watchlist
     * from a single 12k-char brief cleanly (verified 2026-07-01) — which also
     * fixed the "under-extraction of long ticker lists" quality gap that the
     * chunking itself was causing.
     */
    private static final int EXTRACT_CHUNK_SIZE = 80;

    /**
     * A single extraction call over a brief larger than this is chunked even if the
     * comment COUNT is small — purely to stay well inside num_ctx (12 000 chars
     * ≈ 3k tokens + ~800 system + 768 reply ≪ 8192). Validated live at this size.
     */
    private static final int EXTRACT_CHAR_BUDGET = 12_000;

    /** Max characters of COMMENT text per extraction chunk (the shared preamble rides on top). */
    private static final int EXTRACT_CHUNK_CHARS = 9_000;

    /**
     * Extra compose attempts when a subject with NO prior headline comes back empty. 1:1 mirror:
     * a FIRST line is never dropped — the room talking IS the story (we attach price + news), so
     * the only legitimate {@code {"headline":""}} is a redundant UPDATE against the unit's OWN
     * priors. The 4B model occasionally returns empty on a thin/question thread anyway; its random
     * seed varies, so a retry almost always yields the line.
     */
    private static final int FIRST_COMPOSE_EMPTY_RETRIES = 2;

    /**
     * How often a unit whose compose came back unusable (a model whiff, not a
     * deliberate redundant-empty) is re-queued before it's parked. 1 = give it ONE
     * more tick; a second consecutive whiff parks it (stop re-dirtying, but never
     * mark it covered) so it simply waits for fresh evidence to wake it again. A
     * persistently-unpublishable unit can't then loop the model every tick forever.
     */
    private static final int MAX_COMPOSE_RETRIES = 1;

    private final AgentBrain brain;
    private final ClusterRegistry clusterRegistry;
    private final AgentRepository agentRepository;
    private final RedditRepository redditRepository;
    private final ReportBuilder reportBuilder;
    private final TickerResolver tickerResolver;
    private final HeadlineWriter headlineWriter;
    private final SubjectAttributor attributor;
    /** Shared embedding seam — powers the semantic near-duplicate guard here (and tier-2 resolution). */
    private final EmbeddingService embeddings;
    /** Feed-wide subject store — the editorial atom in prod after the #2 cutover. */
    private final SubjectRegistry subjectRegistry;
    /**
     * Per-unit count of consecutive compose whiffs (cleared on a publish, a
     * redundant-empty, or once the unit is parked). Bounds {@link #MAX_COMPOSE_RETRIES}.
     * Concurrent: under the #3 pipeline {@code composeAndPublishUnit} runs on several
     * compose worker threads (for DIFFERENT units), and {@code merge}'s atomic ops here
     * must stay race-free.
     */
    private final Map<String, Integer> composeRetries = new ConcurrentHashMap<>();

    /**
     * Free gemma4 permits right now — for the pipeline's contention logging. The gate itself
     * now lives in {@link AgentBrain} (shared with the vision prefetch, since both hit the one
     * model); prep extraction + compose + vision together never exceed Ollama's NUM_PARALLEL=2.
     */
    public int availableLlmPermits() {
        return brain.availableLlmPermits();
    }

    /**
     * Live config — read fresh each tick, NOT cached in the ctor, so the Settings
     * view (SettingsBridge mutates this same instance) takes effect without a
     * restart: cluster-theme mode (ALLES/NUR TICKER), news-coverage, and the
     * context-relief window all switch live.
     */
    private final GlobalConfig config;

    @Inject
    public EditorialAgent(AgentBrain brain, ClusterRegistry clusterRegistry,
            AgentRepository agentRepository,
            RedditRepository redditRepository,
            ApplicationEventBus eventBus, I18nService i18n,
            YahooFinanceClient yahooFinance, EmbeddingService embeddings,
            SubjectRegistry subjectRegistry, GlobalConfig config) {
        this.brain = brain;
        this.clusterRegistry = clusterRegistry;
        this.agentRepository = agentRepository;
        this.redditRepository = redditRepository;
        this.reportBuilder = new ReportBuilder(redditRepository, brain);
        this.tickerResolver = new TickerResolver(yahooFinance, embeddings); // Tier 2 enabled
        this.headlineWriter = new HeadlineWriter(agentRepository, eventBus);
        this.attributor = new SubjectAttributor(redditRepository, brain);
        this.embeddings = embeddings;
        this.subjectRegistry = subjectRegistry;
        this.config = config;
    }

    /**
     * Installs the live price chain (L&amp;S → Deutsche Börse → NASDAQ → Yahoo, EUR) onto
     * the resolver. Optional Guice method-injection: present in production
     * (AppModule binds {@link PriceSource}), absent in the lab harness + tests,
     * where the resolver keeps the Yahoo-only snapshot path. Fires after the
     * constructor, so the hand-built {@link #tickerResolver} already exists.
     */
    @com.google.inject.Inject(optional = true)
    void setPriceSource(de.bsommerfeld.wsbg.terminal.core.price.PriceSource priceSource) {
        tickerResolver.setPriceSource(priceSource);
    }

    /**
     * Installs the multi-source news pool onto the resolver. Optional Guice
     * method-injection: present in production (AppModule binds the news sources),
     * absent in the lab harness + tests, where news stays Yahoo-only.
     */
    @com.google.inject.Inject(optional = true)
    void setNewsAggregator(de.bsommerfeld.wsbg.terminal.aggregator.NewsAggregator aggregator) {
        tickerResolver.setNewsAggregator(aggregator);
        LOG.info("[NEWS] multi-source aggregator installed on the resolver.");
    }

    /**
     * #2 step 1 — extracts + resolves the cluster's subjects (as the editorial
     * tick does) but, instead of composing, attributes each subject's evidence
     * into the feed-wide {@link SubjectRegistry}. Lets the {@code .lab} harness
     * show how subject units accumulate before per-unit composition exists.
     * Returns the resolved subjects for the trace.
     */
    public List<ResolvedSubject> attributeCluster(String clusterId, SubjectRegistry registry) {
        List<ResolvedSubject> resolved = resolveClusterSubjects(clusterId);
        attributeResolved(clusterId, registry, resolved);
        return resolved;
    }

    /**
     * Step 3a — the <b>lock-free</b> half of {@link #attributeCluster}: builds the
     * cluster brief, extracts subjects (one LLM call), and resolves each to a Yahoo
     * ticker/news/price. Touches <b>no</b> shared {@link SubjectRegistry} state, so the
     * #3 pipeline runs it <em>outside</em> the registry lock — the 10–120 s of LLM +
     * Yahoo work then no longer blocks the merge cadence (which would otherwise starve
     * ALL headline composition behind one in-flight extract). Returns the resolved
     * subjects to hand to {@link #attributeResolved}.
     *
     * <p>Extraction must see ALL evidence (no coverage filter) — a subject named only
     * in an older, already-covered comment must still be extracted and attributed, or
     * its unit would stop accumulating. Coverage is applied at COMPOSE time (the per-unit
     * brief / the cluster-theme brief), never at extraction.
     */
    public List<ResolvedSubject> resolveClusterSubjects(String clusterId) {
        ChatModel model = brain.getAgentModel();
        InvestigationCluster cluster = clusterRegistry.getCluster(clusterId);
        if (model == null || cluster == null) return List.of();

        String brief = reportBuilder.buildReportData(
                cluster, List.of(), brain.getUserLanguage().code());
        Subjects subjects = extractSubjects(model, cluster, brief);
        // The MODEL's event cut: extraction names the protagonist ({primary}) itself —
        // it read the whole thread, so its judgment beats the title/tradeable/count
        // heuristic. Stash the hint for the attribution half (same-cluster prep is
        // single-flight, so this simple handoff map is race-free).
        if (subjects.primaryName().isEmpty()) {
            primaryHints.remove(clusterId);
        } else {
            // Canonicalized like the resolver canonicalizes each query, so the
            // attribution-side match compares like with like.
            primaryHints.put(clusterId, WsbgJargon.canonicalize(subjects.primaryName()));
        }
        int[] relatedAlloc = distributeRelated(subjects.names().size(), RELATED_BUDGET, RELATED_PER_SUBJECT);
        return tickerResolver.resolveAll(subjects.names(), relatedAlloc);
    }

    /** Per-cluster primary-subject hint from extraction, consumed by {@link #attributeResolved}. */
    private final Map<String, String> primaryHints = new ConcurrentHashMap<>();

    /**
     * Step 3b — the registry-mutating half of {@link #attributeCluster}: folds the
     * already-resolved subjects into the feed-wide {@link SubjectRegistry}. This is the
     * <b>only</b> part that touches shared registry state ({@code findOrCreate} +
     * {@code markDirty}), so the #3 pipeline holds the registry READ lock around just
     * this call — shared across concurrent prep folds, exclusive with the merge
     * cadence's write lock. A no-op if the cluster vanished since it was resolved.
     */
    public void attributeResolved(String clusterId, SubjectRegistry registry, List<ResolvedSubject> resolved) {
        if (resolved == null) return;
        InvestigationCluster cluster = clusterRegistry.getCluster(clusterId);
        if (cluster == null) return;
        attributor.attribute(registry, cluster, resolved, primaryHints.remove(clusterId));
    }

    /**
     * #2 step 3 — composes ONE headline for a single {@link SubjectUnit} from its
     * accumulated Reddit evidence + Yahoo data + its OWN prior headlines, and
     * classifies it NEW vs UPDATE (story-continuity). The unit is the editorial
     * atom now, not the cluster. Returns a {@link UnitDraft}; storing the result
     * on the unit / publishing it is the caller's job.
     */
    public UnitDraft composeUnit(SubjectUnit unit) {
        if (unit == null) {
            return new UnitDraft("", "", null, false, false, "", 0, List.of(), false, false, List.of());
        }
        ChatModel model = brain.getComposeModel(); // tight numPredict — one short headline JSON
        if (model == null) {
            return new UnitDraft(unit.id, unit.canonicalName(), null, false, false, "", 0, List.of(), false, false, List.of());
        }
        // Fully localized compose scaffold (German prompt + German room-slang + German brief
        // labels). This was held to English while the compose OUTPUT was a fat 9-field JSON —
        // the German scaffold on top of that big task whitespace-looped the 4B model. Now that
        // the output is slimmed to {headline, highlight, mode}, the model's job is tiny and it
        // commits cleanly regardless of scaffold language (proven: 0 whiffs in run27).
        String lang = brain.getUserLanguage().code();
        String sys = PromptLoader.loadLocalized("headline-compose-unit", lang)
                .replace("{{LANGUAGE}}", brain.getUserLanguage().displayName())
                .replace("{{ROOM_SLANG}}", WsbgJargon.roomSlangForPrompt(lang));
        String user = unitBrief(unit, config.getHeadlines().isNewsCoverageEnabled(), BriefLabels.of(lang));

        boolean hasPriors = !unit.headlines().isEmpty();
        // When the redundancy filter is OFF (user setting), the wire is a strict 1:1 mirror:
        // every dirty signal writes a line, even a duplicate — so an empty reply is NEVER honored
        // and we retry it exactly like a first line. When ON (default), a redundant UPDATE against
        // the unit's own priors is the one legal empty.
        boolean suppressRedundant = config.getHeadlines().isSuppressRedundant();
        long t0 = System.nanoTime();
        String text = null;
        Draft draft = null;
        boolean isUpdate = false;
        boolean salvaged = false;
        boolean redundant = false;
        List<String> citedNews = List.of();
        List<Integer> derivedFrom = List.of();
        // 1:1 mirror: a FIRST line is NEVER dropped. The room talking about a subject IS the
        // story (we attach price + news ourselves), so the only legitimate {"headline":""} is a
        // redundant UPDATE against the unit's OWN priors. The 4B model sometimes lazily returns
        // empty on a thin/question thread even with no priors — retry (its seed varies) before
        // accepting an empty/garbage first compose.
        for (int attempt = 0; ; attempt++) {
            text = chat(model, sys, user);
            draft = null;
            isUpdate = false;
            salvaged = false;
            redundant = false;
            citedNews = List.of();
            derivedFrom = List.of();
            JsonNode obj = firstHeadlineObject(parseJson(text));
            if (obj != null) {
                draft = toDraft(obj);
                isUpdate = "UPDATE".equalsIgnoreCase(obj.path("mode").asText(""));
                citedNews = readStrings(obj.path("sourceNewsIds"));
                derivedFrom = readInts(obj.path("derivedFrom"));
                // Empty headline is redundant ONLY against the unit's own priors AND only when
                // the redundancy filter is on — NEVER for a first line, and never at all in strict
                // 1:1 mode. Otherwise an empty reply is a model lapse, not "nothing to say".
                redundant = draft == null && obj.has("headline") && hasPriors && suppressRedundant;
            }
            if (draft == null && !redundant) {
                for (JsonNode o : salvageObjects(text)) {
                    Draft d = toDraft(o);
                    if (d != null) {
                        draft = d;
                        isUpdate = "UPDATE".equalsIgnoreCase(o.path("mode").asText(""));
                        citedNews = readStrings(o.path("sourceNewsIds"));
                        salvaged = true;
                        break;
                    }
                }
            }
            if (draft == null && !redundant) {
                // Even balanced-object salvage failed (a stray quote like "ticker": null"
                // breaks the object) — recover the headline + scalars by regex so the line
                // isn't lost.
                draft = salvageDraftByRegex(text);
                if (draft != null) {
                    isUpdate = "UPDATE".equalsIgnoreCase(orEmpty(regexStringField(text, "mode")));
                    salvaged = true;
                }
            }
            // Retry an empty/garbage reply when we must NOT drop it: a first line (no priors), or
            // strict 1:1 mode (redundancy filter off → every dirty writes). A usable line, a legit
            // redundant, or (priors AND filter on) → stop.
            if (draft != null || redundant || (hasPriors && suppressRedundant)
                    || attempt >= FIRST_COMPOSE_EMPTY_RETRIES) break;
            LOG.info("[COMPOSE] empty line for {} ({}) — retry {}/{} ({})",
                    unit.id, unit.canonicalName(), attempt + 1, FIRST_COMPOSE_EMPTY_RETRIES,
                    hasPriors ? "1:1 mode, no redundancy filter" : "a first line is never dropped");
        }
        long elapsed = ms(t0, System.nanoTime());
        // A whiff = no usable headline AND the model did NOT deliberately say "redundant"
        // (no headline key at all / wrong shape / garbage). This is the silent-loss case:
        // surface the raw reply — like the extraction warn — so the next one is
        // diagnosable, and let the caller re-queue the unit once before parking it.
        boolean whiffed = draft == null && !redundant;
        if (whiffed) {
            String raw = text == null ? "" : text.strip();
            LOG.warn("[COMPOSE] no usable headline for unit {} ({}) — brief={} chars; raw reply: {}",
                    unit.id, unit.canonicalName(), user.length(),
                    raw.length() > 400 ? raw.substring(0, 400) + "…" : raw);
        }

        // A price/% in the line is UNVERIFIED when we have no resolved market data
        // for the subject — it then comes from the room's own post/screenshot, not
        // from Yahoo. The wire is a sentiment mirror; user numbers aren't facts.
        boolean unverified = mentionsPrice(draft) && !unitHasVerifiedPrice(unit);
        return new UnitDraft(unit.id, unit.canonicalName(), draft, isUpdate, salvaged, text, elapsed,
                citedNews, unverified, whiffed, derivedFrom);
    }

    /** The headline object out of a parsed reply: a bare object, or the first of a {@code {"headlines":[…]}} wrapper. */
    private static JsonNode firstHeadlineObject(JsonNode root) {
        if (root == null) return null;
        if (root.has("headline")) return root;
        if (root.path("headlines").isArray() && !root.path("headlines").isEmpty()) {
            return root.path("headlines").get(0);
        }
        return null;
    }

    /**
     * News older than this is still shown (a quiet subject's only context may be
     * old news) but tagged {@code [STALE]} so the model never sells it as a fresh
     * catalyst. User-chosen range was 24–48h; 36h is the middle. Tunable.
     */
    static final Duration NEWS_STALE_AFTER = Duration.ofHours(36);

    /** Full prior headlines rendered in the brief; older ones collapse into a digest line. */
    static final int PRIOR_HEADLINES_SHOWN = 3;

    /**
     * Rough char budget for the evidence block (~1.5k tokens). A hot unit can pile
     * up more mentions within the TTL than num_ctx absorbs — Ollama would then
     * truncate the prompt SILENTLY, which reads as the model getting dumb. Oldest
     * mentions are dropped first, with an explicit "omitted" line so the model
     * knows the story is longer than what it sees.
     */
    static final int EVIDENCE_CHAR_BUDGET = 4500;

    /** Builds the per-unit brief: Yahoo data + the room's evidence about this subject + its story memory. Static for testability. */
    static String unitBrief(SubjectUnit unit, boolean newsCoverageEnabled) {
        return unitBrief(unit, newsCoverageEnabled, BriefLabels.EN);
    }

    static String unitBrief(SubjectUnit unit, boolean newsCoverageEnabled, BriefLabels lbl) {
        StringBuilder sb = new StringBuilder();
        sb.append(lbl.subjectHeader(unit.canonicalName(), unit.isInstrument() ? unit.ticker() : null));

        Instant now = Instant.now();
        MarketSnapshot s = unit.snapshot();
        if (s != null && s.hasPrice()) {
            // Multi-source now (L&S / Deutsche Börse / NASDAQ / Yahoo) — name the venue,
            // don't hard-code "Yahoo", so the model never mislabels an EUR L&S price.
            String venue = s.exchangeName() == null || s.exchangeName().isBlank() ? lbl.defaultVenue() : s.exchangeName();
            if ("PTS".equals(s.currency())) {
                // A stock index is quoted in points, not a currency — tell the model
                // so the headline reads „DAX unter 24.000 Punkte", never „… Euro".
                sb.append(lbl.liveDataIndex(venue, s.price()));
            } else {
                sb.append(lbl.liveData(venue, s.price(),
                        s.currency() == null || s.currency().isEmpty() ? "" : " " + s.currency()));
            }
            if (Double.isFinite(s.dayChangePercent())) {
                sb.append(lbl.dayMove(s.dayChangePercent()));
            }
            // Multi-day arc (L&S series=history): 5-day + 1-month move and the gap to
            // the 52-week high — raw numbers, no reading. This is what lets the model
            // tell "ran for days, corrects today" from a plain red day, and gives the
            // RUNNER/BREAKOUT/EXTREME_DIRECTION triggers verified data to stand on.
            Double offHigh = (s.hasPrice() && Double.isFinite(s.fiftyTwoWeekHigh())
                    && s.fiftyTwoWeekHigh() > 0 && s.price() < s.fiftyTwoWeekHigh())
                    ? (s.price() - s.fiftyTwoWeekHigh()) / s.fiftyTwoWeekHigh() * 100.0 : null;
            sb.append(lbl.trend(s.changeOverTradingDays(5), s.changeOverTradingDays(21), offHigh));
            // Off-hours honesty: a quote older than 30 min is a last close, not live.
            long quoteAge = now.getEpochSecond() - s.marketTimeEpochSeconds();
            if (s.marketTimeEpochSeconds() > 0 && quoteAge > 1800) {
                sb.append(lbl.marketClosed());
            }
            // Price anchor: where the subject stood when the room first surfaced
            // it. Survives the evidence prune — the "since first mention" arc is
            // story memory, not a Reddit claim (both prices are Yahoo's own).
            Double anchor = unit.firstPrice();
            if (anchor != null && anchor > 0 && unit.firstPriceAt() != null) {
                double sinceFirst = (s.price() - anchor) / anchor * 100.0;
                sb.append(lbl.sinceFirstMention(age(unit.firstPriceAt(), now), sinceFirst, anchor, s.price()));
            }
            sb.append('\n');
        } else if (!unit.isInstrument()) {
            sb.append(lbl.noTicker());
        }

        // News not yet cited by a prior headline for THIS subject (covered ones are
        // filtered so two headlines never rest on the same item). Each carries a
        // [news:ID] the model echoes back in sourceNewsIds to mark it consumed.
        // Old items are kept (no fresh news is also a situation worth reporting
        // from) but tagged STALE so they're never sold as a fresh catalyst.
        List<RawNewsItem> freshNews = new ArrayList<>();
        List<RawNewsItem> toldNews = new ArrayList<>();
        for (RawNewsItem n : unit.news()) {
            // News coverage is OFF by default: news enriches freely and may back
            // several headlines on a topic (it's cached, so reuse is free). Only when
            // explicitly enabled do we hide a unit's already-cited news.
            if (!newsCoverageEnabled || !unit.isNewsCovered(n.uuid())) freshNews.add(n);
            else toldNews.add(n);
        }
        if (!freshNews.isEmpty() || !toldNews.isEmpty()) {
            sb.append(lbl.newsHeader());
            // Already-woven items stay VISIBLE — a known fact remains the anchor the
            // room's next development hangs on — but compact (title only) and tagged,
            // so it frames the next line without being re-sold as fresh news.
            for (RawNewsItem n : toldNews) {
                sb.append("  - ").append(lbl.newsToldTag()).append(' ').append(n.title()).append('\n');
            }
            for (RawNewsItem n : freshNews) {
                sb.append("  - [news:").append(n.uuid() == null || n.uuid().isBlank() ? "?" : n.uuid()).append("] ");
                if (n.publishedAt() != null) {
                    sb.append(lbl.ago(age(n.publishedAt(), now))).append(' ');
                    if (Duration.between(n.publishedAt(), now).compareTo(NEWS_STALE_AFTER) > 0) {
                        sb.append("[STALE] ");
                    }
                    sb.append("— ");
                }
                sb.append(n.title());
                if (n.publisher() != null && !n.publisher().isEmpty()) sb.append(" · ").append(n.publisher());
                if (n.summary() != null && !n.summary().isBlank()) {
                    String sum = n.summary().replace('\n', ' ').strip();
                    sb.append("\n      ").append(sum.length() > 200 ? sum.substring(0, 200) + "…" : sum);
                }
                sb.append('\n');
            }
        }

        // Coverage boundary: evidence added on/before the unit's most recent
        // published headline was already in view when that line was written, so it
        // must NOT seed another headline. We OMIT that covered material here — the
        // story-memory headlines below ARE its context — and show only what arrived
        // SINCE the last headline. Time-based (not model-citation-based): a 4B model
        // under-cites sources, but the unit's own evidence + headline timestamps are
        // exact. Mirrors the per-cluster ReportBuilder coverage.
        long lastHeadlineEpoch = 0L;
        for (SubjectUnit.UnitHeadline h : unit.headlines()) {
            if (h.atEpoch() > lastHeadlineEpoch) lastHeadlineEpoch = h.atEpoch();
        }
        List<SubjectUnit.EvidenceRef> visible = new ArrayList<>();
        int coveredOmitted = 0;
        for (SubjectUnit.EvidenceRef e : unit.evidence()) {
            if (lastHeadlineEpoch > 0 && e.addedAtEpoch() <= lastHeadlineEpoch) {
                coveredOmitted++;
                continue; // already reflected in a prior headline → omit
            }
            visible.add(e);
        }

        // Char budget over the VISIBLE (fresh) refs: keep the NEWEST that fit, drop
        // the oldest, and say so — never let Ollama truncate the prompt silently.
        int start = visible.size();
        int budget = EVIDENCE_CHAR_BUDGET;
        while (start > 0 && budget - visible.get(start - 1).snippet().length() - 24 >= 0) {
            start--;
            budget -= visible.get(start).snippet().length() + 24;
        }
        boolean haveHeadlines = lastHeadlineEpoch > 0;
        sb.append(lbl.evidenceHeader(haveHeadlines));
        if (coveredOmitted > 0) {
            sb.append(lbl.coveredOmitted(coveredOmitted));
        }
        if (start > 0) {
            sb.append(lbl.budgetOmitted(start));
        }
        List<SubjectUnit.EvidenceRef> context = new ArrayList<>();
        for (SubjectUnit.EvidenceRef e : visible.subList(start, visible.size())) {
            if ("reddit-context".equals(e.source())) {
                context.add(e); // a reply chain this subject was named in — rendered below
                continue;
            }
            String loc = "vision".equals(e.source()) ? lbl.visionLoc()
                    : (e.commentId() == null ? e.threadId() : e.commentId());
            sb.append("  - [").append(loc).append(", ")
                    .append(lbl.ago(age(Instant.ofEpochSecond(e.addedAtEpoch()), now))).append("] ")
                    .append(e.snippet()).append('\n');
        }
        if (!context.isEmpty()) {
            sb.append(lbl.conversationContext());
            for (SubjectUnit.EvidenceRef e : context) {
                sb.append("    ↳ ").append(e.snippet()).append('\n');
            }
        }

        appendStoryMemory(sb, unit.headlines(), now, lbl);
        return sb.toString();
    }

    /**
     * The unit's story memory: the last {@link #PRIOR_HEADLINES_SHOWN} headlines in
     * full (with age + sentiment), older ones as a count digest, plus the sentiment
     * arc across the whole history. This block is what survives the evidence prune —
     * without it, a unit older than the TTL looked brand-new and the "no prior
     * headlines → always write" rule re-published the old story verbatim.
     */
    private static void appendStoryMemory(StringBuilder sb, List<SubjectUnit.UnitHeadline> prior,
            Instant now, BriefLabels lbl) {
        if (prior.isEmpty()) return;
        sb.append(lbl.storyMemoryHeader());
        int shownFrom = Math.max(0, prior.size() - PRIOR_HEADLINES_SHOWN);
        if (shownFrom > 0) {
            SubjectUnit.UnitHeadline first = prior.get(0);
            sb.append(lbl.earlierHeadlines(shownFrom, age(Instant.ofEpochSecond(first.atEpoch()), now)));
        }
        // Numbered so the compose reply can CITE the prior lines this one builds on
        // ("derivedFrom": [2]) — provenance chaining: the cited lines' news sources
        // are inherited onto the new line. Ordinals are 1-based within the SHOWN
        // window and re-derived identically in inheritedRefs().
        int ordinal = 1;
        for (SubjectUnit.UnitHeadline h : prior.subList(shownFrom, prior.size())) {
            sb.append("  - #").append(ordinal++)
                    .append(" [").append(lbl.ago(age(Instant.ofEpochSecond(h.atEpoch()), now)));
            if (h.sentiment() != null && !h.sentiment().isBlank()) sb.append(", ").append(h.sentiment());
            sb.append("] ").append(h.text()).append('\n');
        }
        String arc = sentimentArc(prior);
        if (!arc.isEmpty()) sb.append(lbl.sentimentArcPrefix()).append(arc).append('\n');
    }

    /**
     * The unit's sentiment trajectory ("BULLISH → MIXED → BEARISH") across its
     * published headlines, consecutive duplicates collapsed. Empty when fewer than
     * two distinct steps exist — a one-word arc carries no information the
     * headline list doesn't. Package-private for testing.
     */
    static String sentimentArc(List<SubjectUnit.UnitHeadline> prior) {
        List<String> steps = new ArrayList<>();
        for (SubjectUnit.UnitHeadline h : prior) {
            String sent = h.sentiment() == null ? "" : h.sentiment().trim().toUpperCase(Locale.ROOT);
            if (sent.isEmpty()) continue;
            if (steps.isEmpty() || !steps.get(steps.size() - 1).equals(sent)) steps.add(sent);
        }
        return steps.size() < 2 ? "" : String.join(" → ", steps);
    }

    /** Compact relative age: "5m", "3h", "2d". Clamps negative (clock skew) to "0m". */
    static String age(Instant then, Instant now) {
        long mins = Math.max(0, Duration.between(then, now).toMinutes());
        return mins < 60 ? mins + "m" : mins < 1440 ? (mins / 60) + "h" : (mins / 1440) + "d";
    }

    /**
     * One per-unit compose result (#2 step 3). {@code draft} is null when the model
     * wrote no usable headline. {@code citedNewsIds} = the news the line leaned on
     * (step 3b). {@code unverified} = the line carries a price/% that did NOT come
     * from our data sources (no resolved Yahoo data) — it's a user-posted number, to
     * be shown with an "unverified" marker, never as fact.
     */
    public record UnitDraft(String unitId, String label, Draft draft, boolean isUpdate,
            boolean salvaged, String raw, long ms, List<String> citedNewsIds, boolean unverified,
            boolean whiffed, List<Integer> derivedFrom) {}

    /**
     * The production editorial tick. One dirty signal (a cluster that gained fresh
     * content) drives TWO independent headline producers, never deduped against
     * each other:
     *
     * <ul>
     *   <li>the <b>cluster/thread</b> — one THEME headline per dirty cluster (the
     *       thread's narrative, the room's juxtaposition), keyed by cluster id;</li>
     *   <li>the feed-wide <b>{@link SubjectUnit}</b> — one headline per dirty unit
     *       (a subject tracked across the whole feed), keyed by unit id.</li>
     * </ul>
     *
     * Steps (mirrors the proven {@code LabRunner.run} flow on the singleton
     * {@link SubjectRegistry}):
     * <ol>
     *   <li><b>context relief</b> — prune evidence older than the snapshot TTL;</li>
     *   <li><b>per dirty cluster</b> — attribute its subjects into the registry
     *       (marks units dirty) AND publish its theme headline;</li>
     *   <li><b>identity-merge</b> name units into their ticker unit;</li>
     *   <li><b>compose + publish</b> ONE headline per dirty unit (NEW/UPDATE), via
     *       {@link HeadlineWriter#publishUnit}.</li>
     * </ol>
     *
     * <p>Clusters are the ingestion layer (ClusterEngine assigns one cluster per
     * thread) AND, now, the theme producer. A dirty cluster is both the signal that
     * fresh evidence arrived and an entity that gets its own line. Collation is
     * intentionally NOT run here (deferred).
     */
    public void runUnitTick(Set<String> dirtyClusterIds) {
        if (dirtyClusterIds == null || dirtyClusterIds.isEmpty()) return;
        ChatModel model = brain.getAgentModel();
        if (model == null) {
            LOG.warn("EditorialAgent: agent model not ready, skipping unit tick.");
            return;
        }

        // 1 — context relief (same rolling window the lab + snapshot TTL use).
        long contextTtlMinutes = config.getReddit().getSnapshotTtlMinutes();
        if (contextTtlMinutes > 0) {
            subjectRegistry.pruneContentOlderThan(Duration.ofMinutes(contextTtlMinutes));
        }

        // 2 — per dirty cluster, drive BOTH producers from the one dirty signal:
        //     (a) attribute its subjects into the feed-wide registry (marks units
        //         dirty for step 4), and
        //     (b) publish the cluster's own THEME headline — the thread narrative /
        //         the room's juxtaposition. The two producers are independent and
        //         intentionally NOT deduped (the connected thread context is a
        //         different truth from the per-subject line).
        int themePublished = 0;
        for (String id : dirtyClusterIds) {
            try {
                // attributeCluster always runs — it feeds the per-subject units.
                // The cluster's OWN theme line is opt-in (off by default): every
                // thread would otherwise get a line, flooding the wire with generic
                // narratives and overlapping the per-subject headlines.
                List<ResolvedSubject> resolved = attributeCluster(id, subjectRegistry);
                if (config.getHeadlines().isClusterThemeEnabled()) {
                    themePublished += publishClusterTheme(model, id, resolved);
                }
            } catch (Exception e) {
                LOG.warn("EditorialAgent: cluster {} failed: {}", id, e.getMessage());
            }
        }

        // 3 — fold name units into their ticker unit (conservative, never swallows).
        subjectRegistry.mergeIdentities();

        // 4 — compose + publish one headline per dirty unit, heaviest evidence first.
        Set<String> dirtyUnits = subjectRegistry.drainDirty();
        List<SubjectUnit> toCompose = dirtyUnits.stream()
                .map(subjectRegistry::get).filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(SubjectUnit::evidenceCount).reversed())
                .toList();
        int published = 0;
        for (SubjectUnit u : toCompose) {
            try {
                if (composeAndPublishUnit(u)) published++;
            } catch (Exception e) {
                LOG.warn("EditorialAgent: unit {} failed: {}", u.id, e.getMessage());
            }
        }
        LOG.info("[AGENT] tick done: {} cluster(s) → {} theme + {} unit headline(s) ({} dirty unit(s))",
                dirtyClusterIds.size(), themePublished, published, toCompose.size());
    }

    /**
     * Composes + publishes ONE headline for a dirty {@link SubjectUnit} (seed story
     * memory → {@link #composeUnit} → QA-write), routing the empty/whiff/redundant
     * cases through {@link #handleEmptyCompose}. Extracted from {@link #runUnitTick}
     * so the #3 producer/consumer pipeline's compose worker and the fallback batch
     * tick drive the <em>exact same</em> per-unit logic.
     *
     * <p>Thread-safe for parallel workers on DIFFERENT units: per-unit state is
     * synchronized on the unit, {@link #composeRetries} is concurrent, and the model
     * call inside {@link #composeUnit} is semaphore-gated. Returns whether a headline
     * was actually published (false on empty/whiff/redundant/unchanged/QA-reject).
     */
    public boolean composeAndPublishUnit(SubjectUnit u) {
        if (u == null) return false;
        // Snapshot the evidence version BEFORE composing: every non-whiff outcome below
        // stamps it back, so the merge cadence won't re-compose this unit until fresh
        // evidence arrives. Captured up front (not at publish) so evidence racing in
        // mid-compose leaves the unit eligible for a genuine follow-up.
        long composedV = u.evidenceVersion();
        seedHeadlineHistoryIfEmpty(u);
        UnitDraft ud = composeUnit(u);
        Draft d = ud.draft();
        if (d == null || d.headline() == null || d.headline().isBlank()) {
            handleEmptyCompose(u, ud);
            // BOTH a deliberate redundant-empty AND a whiff (the model usually MEANT to skip
            // but emitted nothing parseable instead of {"headline":""}) count as composed
            // against THIS evidence: do NOT busy-retry. The unit only re-composes once
            // GENUINELY new evidence bumps its version — idle workers are fine, churn is not.
            u.markComposedAt(composedV);
            return false;
        }
        // Unchanged from the unit's last line → nothing to publish (but it WAS composed).
        if (d.headline().equalsIgnoreCase(u.lastHeadlineText())) {
            composeRetries.remove(u.id);
            u.markComposedAt(composedV);
            return false;
        }
        // Semantic near-duplicate guard (2026-07-01): the 4B model sometimes re-words
        // an already-published line as a fresh NEW instead of returning the
        // redundant-empty (live: two "Absturz trifft die überbewerteten KI-Giganten
        // wie Nvidia" lines ~70 s apart) — token-Jaccard misses the paraphrase,
        // embeddings don't. A line semantically ≥ SEMANTIC_DUP_SIM to a recent prior
        // AND carrying no number the prior didn't (a fresh figure IS a development)
        // is the same story re-worded → treated like a deliberate redundant-empty.
        // Skipped in strict 1:1 mode, like the writer's own guard.
        if (config.getHeadlines().isSuppressRedundant()
                && isSemanticRepeat(d.headline(), u.headlines())) {
            LOG.info("[COMPOSE] semantic near-duplicate for unit {} — dropped (same story re-worded)", u.id);
            composeRetries.remove(u.id);
            u.markComposedAt(composedV);
            return false;
        }
        // News provenance is DETERMINISTIC and LINE-scoped — earned only by USE: an
        // item counts when the published line actually WOVE IT IN (significant-token
        // overlap between line and news title/summary — the "konkret eingewoben"
        // test). The tag and its clickable source list promise "the articles this
        // line leans on"; the old unit-scoped flag (!u.news().isEmpty()) lit the tag
        // on EVERY line of a news-rich subject, even a pure room-sentiment line that
        // used none of it (live: a Microsoft chart-waiting line carrying a pool of
        // unrelated articles). The 4B's own citations stay out of it — it under-cites;
        // the woven-in check is exact where a model citation never was.
        List<de.bsommerfeld.wsbg.terminal.source.RawNewsItem> newsUsed = u.news().stream()
                .filter(n -> headlineReflectsNews(d.headline(), n))
                .toList();
        // Provenance chaining: the model cites the numbered prior lines it built on
        // ("derivedFrom": [2]) — those lines' news sources carry over, so a fact that
        // debuted on an earlier, tagged line keeps its sources on every continuation
        // (without this, paraphrase through the story memory laundered provenance away).
        List<de.bsommerfeld.wsbg.terminal.db.HeadlineNewsRef> inherited =
                inheritedRefs(u.headlines(), ud.derivedFrom(),
                        agentRepository.getHeadlinesByClusterId(u.id));
        u.markComposedAt(composedV);
        if (headlineWriter.publishUnit(u, d, newsUsed, inherited,
                config.getHeadlines().isSuppressRedundant())) {
            composeRetries.remove(u.id); // story moved on → fresh retry budget
            u.addHeadline(d.headline(), ud.isUpdate(), d.sentiment());
            // Coverage marking rides the same woven-in list: a sentiment-only line
            // leaves its news fresh for the next compose (the next line orients on
            // prior headlines instead of re-milking the same item); another unit
            // pulling the same item is untouched (covered ids live on the unit).
            u.markNewsCovered(newsUsed.stream()
                    .map(de.bsommerfeld.wsbg.terminal.source.RawNewsItem::uuid)
                    .filter(Objects::nonNull).toList());
            return true;
        }
        return false;
    }

    /** Similarity above which a new line counts as the same story re-worded. Calibrated
     *  2026-07-02 on live wire pairs (embeddinggemma): actual re-words scored 0.80–1.00
     *  ("Copilot-Bots bündelt Microsoft…" vs "Microsoft bündelt Copilot-Bots…" = 0.88),
     *  genuinely-new angles on the same subject scored ≤ 0.58 — the old 0.92 sat above
     *  the entire re-word band and caught nothing. 0.78 keeps ~0.2 margin to both sides. */
    static final double SEMANTIC_DUP_SIM = 0.78;
    /** Above this, the line is the SAME SENTENCE with at most a ticked figure — the
     *  novel-number exception does not apply (a re-stated day-move is not a development). */
    static final double SEMANTIC_DUP_HARD_SIM = 0.92;
    /** Only priors this recent are compared — an old story may legitimately resurface.
     *  2h: live pairs 1.5h apart were still verbatim re-tells of an unmoved story. */
    private static final long SEMANTIC_DUP_WINDOW_SECS = 7200;

    /**
     * True when {@code line} is a semantic re-word of one of the unit's recent
     * headlines: embedding similarity ≥ {@link #SEMANTIC_DUP_SIM} against a prior
     * from the last {@link #SEMANTIC_DUP_WINDOW_SECS}, UNLESS the new line carries
     * a number the prior didn't — a fresh figure is a real development, never a
     * re-word. That exception is itself capped: at ≥ {@link #SEMANTIC_DUP_HARD_SIM}
     * the "new" line is the same sentence with a ticked figure (the day-move
     * updating is not a development — the quote strip carries it live), so it is a
     * dup regardless. Fail-open: an embedding error never blocks a publish.
     */
    private boolean isSemanticRepeat(String line, List<SubjectUnit.UnitHeadline> priors) {
        if (embeddings == null || line == null || line.isBlank() || priors.isEmpty()) return false;
        long now = Instant.now().getEpochSecond();
        try {
            for (int i = priors.size() - 1; i >= 0; i--) {
                SubjectUnit.UnitHeadline prior = priors.get(i);
                if (now - prior.atEpoch() > SEMANTIC_DUP_WINDOW_SECS) break; // list is chronological
                double sim = embeddings.similarity(line, prior.text());
                if (sim >= SEMANTIC_DUP_HARD_SIM) return true; // same sentence, ticked figure at most
                if (hasNovelNumber(line, prior.text())) continue; // a fresh figure IS a development
                if (sim >= SEMANTIC_DUP_SIM) return true;
            }
        } catch (Exception e) {
            LOG.debug("semantic dup check failed (fail-open): {}", e.getMessage());
        }
        return false;
    }

    private static final Pattern NUMBER_TOKEN = Pattern.compile("\\d+(?:[.,]\\d+)?");

    /** True when {@code line} contains a number token that {@code prior} does not. */
    static boolean hasNovelNumber(String line, String prior) {
        if (line == null) return false;
        String p = prior == null ? "" : prior;
        Matcher m = NUMBER_TOKEN.matcher(line);
        while (m.find()) {
            if (!p.contains(m.group())) return true;
        }
        return false;
    }

    /**
     * Compose-worker entry point for a cluster THEME job (#3): composes + publishes the
     * cluster's theme headline from the prep-stage resolved subjects (passed in so the
     * worker does no I/O), delegating to the unchanged {@link #publishClusterTheme}.
     * Returns how many lines were published (0 or 1).
     */
    public int runThemeJob(String clusterId, List<ResolvedSubject> resolved) {
        ChatModel model = brain.getAgentModel();
        if (model == null) return 0;
        return publishClusterTheme(model, clusterId, resolved);
    }

    /**
     * Handles a dirty unit whose compose yielded no usable headline. A deliberate
     * redundant-empty (this unit's own story is fully covered) is the normal,
     * intended case — drop it silently and clear its retry budget. A whiff (the model
     * returned nothing usable, {@link UnitDraft#whiffed()}) must NOT be lost: re-queue
     * the unit for the next tick up to {@link #MAX_COMPOSE_RETRIES}; a further whiff
     * <em>parks</em> it — no more re-dirty, and crucially never marked covered — so it
     * sleeps until the attributor re-dirties it with genuinely fresh evidence. This is
     * a mechanical robustness fallback, not an editorial skip: the desk always wanted
     * the line, the model just failed to emit one.
     */
    private void handleEmptyCompose(SubjectUnit u, UnitDraft ud) {
        composeRetries.remove(u.id);
        // No busy-retry on a whiff anymore (the caller marks the unit composed against this
        // evidence, so it simply waits for genuinely-new evidence to re-wake it). The empty
        // reply is almost always the model MEANING to skip — re-firing it on the same evidence
        // just whiffs again and floods the 4B model, starving the wire. Logged for diagnostics.
        if (ud.whiffed()) {
            LOG.info("[COMPOSE] unit {} produced no usable headline — waiting for fresh evidence (no retry)", u.id);
        }
    }

    /**
     * Composes and publishes ONE cluster-theme headline for a dirty cluster — the
     * thread's own narrative / the room's juxtaposition, complementary to the
     * per-subject unit lines (and never deduped against them). Keyed by cluster id,
     * so it inherits {@link ReportBuilder}'s coverage (only fresh, uncovered
     * material composes → a re-dirtied thread writes a follow-up over just the new
     * comments) and the identical-text guard for free. Ticker/snapshot are validated
     * against {@code resolved} (the resolver, never the model). Returns 1 if a line
     * was published, else 0.
     */
    private int publishClusterTheme(ChatModel model, String clusterId, List<ResolvedSubject> resolved) {
        InvestigationCluster cluster = clusterRegistry.getCluster(clusterId);
        if (cluster == null) return 0;
        String brief = reportBuilder.buildReportData(
                cluster, agentRepository.getHeadlinesByClusterId(clusterId), brain.getUserLanguage().code());
        Draft d = composeTheme(model, brief);
        if (d == null || d.headline() == null || d.headline().isBlank()) return 0;
        return headlineWriter.publish(cluster, d, resolved) ? 1 : 0;
    }

    /**
     * Restart continuity: a {@link SubjectUnit} is rebuilt from fresh evidence
     * after a process restart (units aren't snapshotted), so its in-memory headline
     * history starts empty — and the compose prompt's "no prior headlines → always
     * write" rule would then re-publish a line the archive already holds as NEW.
     * Seed the unit's history (chronological) from the permanent archive under its
     * own id the first time we touch it, so NEW/UPDATE survives a cold restart. The
     * archive query is the last-24h wire window — exactly the story horizon.
     */
    private void seedHeadlineHistoryIfEmpty(SubjectUnit unit) {
        if (!unit.headlines().isEmpty()) return;
        // getHeadlinesByClusterId returns ascending by createdAt → chronological,
        // and we keep each headline's ORIGINAL publish time so the covered-evidence
        // boundary in unitBrief stays correct after a cold restart.
        for (HeadlineRecord r : agentRepository.getHeadlinesByClusterId(unit.id)) {
            unit.seedHeadline(r.headline(),
                    r.sentiment() == null ? "" : r.sentiment().name(), r.createdAt());
        }
    }

    /**
     * The "konkret eingewoben" test: the published line reflects a news item when it
     * shares at least {@link #NEWS_WOVEN_MIN_OVERLAP} significant tokens (length ≥ 4,
     * umlaut-normalised) with the item's title+summary — company names, event words,
     * figures. A sentiment-only line shares none and leaves the item uncovered.
     * Package-private for testing.
     */
    static final int NEWS_WOVEN_MIN_OVERLAP = 2;

    static boolean headlineReflectsNews(String headline, RawNewsItem n) {
        if (headline == null || n == null) return false;
        Set<String> line = significantTokens(headline);
        Set<String> news = significantTokens(nz(n.title()) + " " + nz(n.summary()));
        int overlap = 0;
        for (String tok : line) {
            if (news.contains(tok) && ++overlap >= NEWS_WOVEN_MIN_OVERLAP) return true;
        }
        return false;
    }

    private static Set<String> significantTokens(String s) {
        Set<String> out = new java.util.HashSet<>();
        for (String w : SubjectAttributor.deUmlaut(s).split("[^a-z0-9]+")) {
            if (w.length() >= 4) out.add(w);
        }
        return out;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** Elapsed milliseconds between two {@link System#nanoTime()} reads. */
    private static long ms(long startNanos, long endNanos) {
        return (endNanos - startNanos) / 1_000_000L;
    }

    /**
     * Spreads a shared pool of {@code budget} related-instrument lookups evenly
     * across {@code n} subjects — round-robin: everyone gets 1 before anyone
     * gets a 2nd — capped at {@code perSubject} each. So 24 over 24 subjects = 1
     * each; over 6 = 4 each; over 25 the 25th gets 0.
     */
    static int[] distributeRelated(int n, int budget, int perSubject) {
        int[] alloc = new int[Math.max(0, n)];
        int remaining = budget;
        boolean progress = true;
        while (remaining > 0 && progress) {
            progress = false;
            for (int i = 0; i < alloc.length && remaining > 0; i++) {
                if (alloc[i] < perSubject) {
                    alloc[i]++;
                    remaining--;
                    progress = true;
                }
            }
        }
        return alloc;
    }

    // ---- Stage 1: subject extraction ----

    private Subjects extractSubjects(ChatModel model, InvestigationCluster cluster, String brief) {
        String sys = PromptLoader.loadLocalized("subject-extraction", brain.getUserLanguage().code())
                .replace("{{ENTITY_ALIASES}}", WsbgJargon.entityAliasesForPrompt());

        int comments = countComments(cluster);
        if (comments <= EXTRACT_CHUNK_SIZE && brief.length() <= EXTRACT_CHAR_BUDGET) {
            // Common path: one call over the full rich brief (vision, poll,
            // covered-split all intact) — only when it's small enough to be safe.
            String text = extractChat(model, sys, brief);
            Extraction ex = parseExtraction(text);
            List<String> out = dedupClean(ex.names());
            String primary = cleanSubjectName(ex.primary());
            if (out.isEmpty()) {
                String raw = text == null ? "" : text.strip();
                LOG.warn("[EXTRACT] 0 subjects — brief={} chars (~{} tok), system={} chars; raw reply: {}",
                        brief.length(), brief.length() / 4, sys.length(),
                        raw.length() > 400 ? raw.substring(0, 400) + "…" : raw);
            }
            return new Subjects(out, primary, out.size(), text);
        }

        // Many comments → batch the extraction so each output array stays short
        // and reliable, then union the names. No comment is dropped. Each batch
        // votes its own primary; the most-voted name wins (first-seen on a tie) —
        // a monster thread is usually a container anyway, and the attribution
        // heuristic (initial title) still backstops a bad vote.
        List<String> chunks = commentChunks(cluster, EXTRACT_CHUNK_SIZE);
        Map<String, String> union = new LinkedHashMap<>(); // lower-case key → first-seen spelling
        Map<String, Integer> primaryVotes = new LinkedHashMap<>();
        for (String chunk : chunks) {
            String text = extractChat(model, sys, chunk);
            Extraction ex = parseExtraction(text);
            for (String name : ex.names()) {
                String clean = cleanSubjectName(name);
                if (!clean.isEmpty()) union.putIfAbsent(clean.toLowerCase(Locale.ROOT), clean);
            }
            String p = cleanSubjectName(ex.primary());
            if (!p.isEmpty()) primaryVotes.merge(p.toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        String primary = primaryVotes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> union.getOrDefault(e.getKey(), e.getKey()))
                .orElse("");
        LOG.info("[EXTRACT] chunked {} comments into {} batch(es) → {} unique subject(s), primary '{}'",
                comments, chunks.size(), union.size(), primary);
        List<String> names = new ArrayList<>(union.values());
        return new Subjects(names, primary, names.size(), "<chunked: " + chunks.size() + " batch(es)>");
    }

    /**
     * One extraction model call, with a single retry on a degenerate BLANK reply. The 4B
     * model occasionally returns an empty string in JSON mode even on a small input (a random
     * degeneration, not a real "no subjects" — that comes back as {@code {"subjects":[]}}).
     * Ollama uses a fresh random seed per call, so the retry genuinely varies. A legit empty
     * array is returned as-is (no retry); only a truly blank reply is retried.
     */
    private String extractChat(ChatModel model, String sys, String input) {
        String text = chat(model, sys, input);
        if (text == null || text.strip().isEmpty()) {
            text = chat(model, sys, input);
        }
        return text;
    }

    /**
     * A transcribed price/move tail glued onto a subject name — a decimal number
     * (1.234 or 1,23), a currency/percent symbol, or a trend arrow, and everything
     * after it. Plain integers ("S&P 500", "3M") are NOT matched, so numeric names
     * survive.
     */
    private static final Pattern PRICE_TAIL =
            Pattern.compile("\\s*(?:\\d+[.,]\\d|[€$£%]|▲|▼|↑|↓).*$");

    /**
     * Strips a screenshot-row price tail from a subject name so the identity stays
     * clean ("Micron Technology 772,30 € ▼ 9,23 %" → "Micron Technology"), while a
     * legitimately-numeric name ("S&P 500", "3M") is left intact. Without this a
     * watchlist row would resolve to no ticker and fragment into per-price units.
     */
    /**
     * Spaced-out all-caps ticker, e.g. an OCR'd watchlist row read as "O T L K".
     * A run of ≥3 single capitals separated only by spaces is collapsed to one
     * token ("O T L K" → "OTLK") so it resolves as the ticker it is. Bounded to
     * single letters so ordinary multi-word names ("Take Two") are untouched.
     */
    private static final Pattern SPACED_TICKER =
            Pattern.compile("\\b([A-Z](?:\\s+[A-Z]){2,5})\\b");

    static String cleanSubjectName(String name) {
        if (name == null) return "";
        String cut = PRICE_TAIL.matcher(name.strip()).replaceFirst("").strip();
        if (cut.isEmpty()) cut = name.strip();
        Matcher m = SPACED_TICKER.matcher(cut);
        if (m.find()) {
            cut = m.replaceAll(mr -> mr.group(1).replaceAll("\\s+", ""));
        }
        return cut;
    }

    /** Cleans each name and dedups case-insensitively, keeping first-seen spelling + order. */
    private static List<String> dedupClean(List<String> names) {
        Map<String, String> seen = new LinkedHashMap<>();
        for (String n : names) {
            String c = cleanSubjectName(n);
            if (!c.isEmpty()) seen.putIfAbsent(c.toLowerCase(Locale.ROOT), c);
        }
        return new ArrayList<>(seen.values());
    }

    /**
     * The model's event cut out of one extraction reply: the {@code primary}
     * (protagonist — the entity the headline will be about, tradeable or not) and
     * {@code names} = primary first + every related subject. Both legacy shapes
     * still parse: a bare {@code {"subjects":[…]}} yields an empty primary (the
     * attribution heuristic then decides).
     */
    record Extraction(String primary, List<String> names) {
        static final Extraction EMPTY = new Extraction("", List.of());
    }

    /** Strict parse of the extraction reply, with a salvage pass for a broken/truncated reply. */
    private Extraction parseExtraction(String text) {
        String primary = "";
        List<String> out = new ArrayList<>();
        JsonNode root = parseJson(text);
        if (root != null) {
            primary = root.path("primary").asText("").trim();
            if (!primary.isEmpty()) out.add(primary);
            for (JsonNode s : root.path("related")) {
                String name = s.asText("").trim();
                if (!name.isEmpty()) out.add(name);
            }
            // Legacy shape ({"subjects":[…]}) — pre-primary replies and old snapshots.
            for (JsonNode s : root.path("subjects")) {
                String name = s.asText("").trim();
                if (!name.isEmpty()) out.add(name);
            }
        }
        if (out.isEmpty()) {
            // The 4B model occasionally emits a malformed/truncated array (long
            // arrays are where it degenerates) → recover whatever names came
            // through intact rather than losing the whole batch.
            String salvagedPrimary = regexStringField(text, "primary");
            if (salvagedPrimary != null && !salvagedPrimary.isBlank()) {
                primary = salvagedPrimary.trim();
                out.add(primary);
            }
            List<String> salvaged = new ArrayList<>(salvageArrayNames(text, "related"));
            salvaged.addAll(salvageArrayNames(text, "subjects"));
            if (!salvaged.isEmpty()) {
                LOG.warn("[EXTRACT] strict parse failed, salvaged {} subject name(s)", salvaged.size());
                out.addAll(salvaged);
            }
        }
        return out.isEmpty() ? Extraction.EMPTY : new Extraction(primary, out);
    }

    private int countComments(InvestigationCluster cluster) {
        int n = 0;
        for (String tid : cluster.activeThreadIds) {
            n += redditRepository.getCommentsForThread(tid, 0).size();
        }
        return n;
    }

    /**
     * Splits the cluster's comments into batches of {@code perChunk}, each prefixed
     * with the same thread-title/body preamble so every batch carries enough
     * context to name subjects. Comment-derived only (the rich brief's vision/poll
     * niceties matter far less on a thread big enough to need batching, which is by
     * definition a comment-heavy text thread).
     */
    private List<String> commentChunks(InvestigationCluster cluster, int perChunk) {
        StringBuilder preamble = new StringBuilder("THREADS IN THIS CLUSTER:\n");
        List<String> lines = new ArrayList<>();
        for (String tid : cluster.activeThreadIds) {
            RedditThread t = redditRepository.getThread(tid);
            if (t == null) continue;
            preamble.append("- ").append(oneLine(t.title()));
            if (t.textContent() != null && !t.textContent().isBlank()) {
                preamble.append(" — ").append(oneLine(t.textContent()));
            }
            preamble.append('\n');
            // Image transcripts are normal context too — fold the thread's + its
            // comments' cached vision into the preamble so screenshot-only subjects
            // (portfolio holdings, watchlists, memes) get named in extraction.
            String vis = threadVision(t, tid);
            if (!vis.isEmpty()) {
                preamble.append("  [images]: ").append(oneLine(vis)).append('\n');
            }
            for (RedditComment c : redditRepository.getCommentsForThread(tid, 0)) {
                if (c.body() == null || c.body().isBlank()) continue;
                lines.add("- " + oneLine(c.body()));
            }
        }
        List<String> chunks = new ArrayList<>();
        if (lines.isEmpty()) {
            chunks.add(preamble.toString());
            return chunks;
        }
        int i = 0;
        while (i < lines.size()) {
            StringBuilder sb = new StringBuilder(preamble);
            sb.append("\nCOMMENTS (batch ").append(chunks.size() + 1).append("):\n");
            // Stop a batch at perChunk lines OR the char budget, whichever comes first —
            // a few long comments must not balloon one batch back into the degenerate zone.
            // `count == 0` forces at least one line so the loop always advances.
            int count = 0, commentChars = 0;
            while (i < lines.size() && count < perChunk
                    && (count == 0 || commentChars < EXTRACT_CHUNK_CHARS)) {
                String line = lines.get(i);
                sb.append(line).append('\n');
                commentChars += line.length() + 1;
                i++;
                count++;
            }
            chunks.add(sb.toString());
        }
        return chunks;
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').strip();
    }

    /** Joined cached vision transcripts for a thread's images + its comments' images (cache-only). */
    private String threadVision(RedditThread t, String threadId) {
        StringBuilder sb = new StringBuilder();
        appendVision(sb, t.imageUrls());
        for (RedditComment c : redditRepository.getCommentsForThread(threadId, 0)) {
            appendVision(sb, c.imageUrls());
        }
        return sb.toString().strip();
    }

    private void appendVision(StringBuilder sb, List<String> urls) {
        if (urls == null) return;
        for (String url : urls) {
            String d = brain.describeImageIfCached(url);
            if (d != null && !d.isBlank()) sb.append(d).append('\n');
        }
    }

    /**
     * Stage-1 output: the named subjects (uncapped, primary first), the model's
     * primary pick ({@code ""} when the reply carried none — legacy shape or no
     * market subject), their count, and the raw reply.
     */
    private record Subjects(List<String> names, String primaryName, int namedByModel, String raw) {}

    /**
     * Recovers subject names from a reply whose JSON is broken/truncated: finds the
     * {@code "subjects"} key, takes the array body (to the closing {@code ]} or — if
     * the reply was cut off — to the end), and pulls every complete quoted string.
     * A truncated final entry (no closing quote) is simply skipped, so we keep
     * whatever names came through intact rather than losing all of them.
     * Package-private for testing.
     */
    static List<String> salvageSubjectNames(String text) {
        return salvageArrayNames(text, "subjects");
    }

    /** Pulls every complete quoted string out of the (possibly truncated) array under {@code key}. */
    static List<String> salvageArrayNames(String text, String key) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        int at = text.indexOf("\"" + key + "\"");
        if (at < 0) return out;
        int lb = text.indexOf('[', at);
        if (lb < 0) return out;
        int rb = text.indexOf(']', lb);
        String arr = rb < 0 ? text.substring(lb) : text.substring(lb, rb);
        Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(arr);
        while (m.find()) {
            String name = m.group(1).trim();
            if (!name.isEmpty()) out.add(name);
        }
        return out;
    }

    // ---- Stage 3: headline composition ----

    /**
     * Composes the cluster's THEME headline — the thread's own narrative / the
     * room's juxtaposition (a separate producer from the per-subject unit lines,
     * never deduped against them). Reads the whole cluster brief (no per-subject
     * Yahoo block — a theme is the conversation, not one instrument) and returns a
     * {@link Draft}, or {@code null} when the thread carries nothing worth a line.
     */
    public Draft composeTheme(ChatModel model, String brief) {
        // Fully localized like composeUnit — safe now that the output schema is slim.
        String lang = brain.getUserLanguage().code();
        String sys = PromptLoader.loadLocalized("headline-theme", lang)
                .replace("{{LANGUAGE}}", brain.getUserLanguage().displayName())
                .replace("{{ROOM_SLANG}}", WsbgJargon.roomSlangForPrompt(lang));
        return parseDraft(chat(model, sys, brief));
    }

    /**
     * Parses a single-object compose reply into a {@link Draft}, with the same
     * recovery chain the per-subject path uses: strict object → first of a
     * {@code headlines} array → balanced-brace salvage → regex salvage. Returns
     * {@code null} when nothing parseable (or an empty headline) is found.
     */
    static Draft parseDraft(String text) {
        JsonNode root = parseJson(text);
        if (root != null) {
            if (root.has("headline")) {
                Draft d = toDraft(root);
                if (d != null) return d;
            } else if (root.path("headlines").isArray() && !root.path("headlines").isEmpty()) {
                Draft d = toDraft(root.path("headlines").get(0));
                if (d != null) return d;
            }
        }
        for (JsonNode obj : salvageObjects(text)) {
            Draft d = toDraft(obj);
            if (d != null) return d;
        }
        return salvageDraftByRegex(text);
    }

    /**
     * Best-effort recovery: parses every <em>balanced</em> top-level
     * {@code {...}} in the reply independently, skipping any that fail (e.g. a
     * truncated tail). String-aware (ignores braces inside quotes) and
     * brace-depth-aware (handles nested {@code subjects} objects). Used when the
     * strict parse rejected the whole reply.
     */
    private static List<JsonNode> salvageObjects(String text) {
        List<JsonNode> out = new ArrayList<>();
        if (text == null) return out;
        int from = text.indexOf('{');
        if (from < 0) return out;

        int depth = 0;
        int objStart = -1;
        boolean inStr = false;
        boolean esc = false;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            switch (c) {
                case '"' -> inStr = true;
                case '{' -> { if (depth == 0) objStart = i; depth++; }
                case '}' -> {
                    if (depth > 0 && --depth == 0 && objStart >= 0) {
                        try {
                            out.add(JSON.readTree(text.substring(objStart, i + 1)));
                        } catch (Exception ignored) {
                            // incomplete/garbled object — skip just this one
                        }
                        objStart = -1;
                    }
                }
                default -> { /* ignore */ }
            }
        }
        return out;
    }

    private static Draft toDraft(JsonNode h) {
        String headline = h.path("headline").asText("").trim();
        if (headline.isEmpty()) return null;
        Double priceMove = h.path("priceMovePercent").isNumber()
                ? h.path("priceMovePercent").asDouble() : null;
        List<DraftSubject> subjects = new ArrayList<>();
        if (h.path("subjects").isArray()) {
            for (JsonNode s : h.path("subjects")) {
                String name = s.path("name").asText("").trim();
                String ticker = s.path("ticker").asText("").trim();
                if (!name.isEmpty() && !ticker.isEmpty()) {
                    subjects.add(new DraftSubject(name, ticker));
                }
            }
        }
        return new Draft(
                headline,
                h.path("sentiment").asText(""),
                h.path("highlight").asText(""),
                h.path("trigger").asText(""),
                emptyToNull(h.path("tickerSymbol").asText("")),
                subjects,
                priceMove,
                readStrings(h.path("sectors")),
                emptyToNull(h.path("assetClass").asText("")),
                readStrings(h.path("sourceThreadIds")),
                readStrings(h.path("sourceCommentIds")));
    }

    /**
     * Last-resort draft recovery when the reply is JSON that even {@link #salvageObjects}
     * can't parse — e.g. the 4B model emits {@code "ticker": null"} (a stray quote) and
     * breaks the whole object, losing a perfectly good headline. We pull the fields out
     * by regex instead, so the line still publishes. Array fields (subjects/ids) are
     * skipped — the headline + its scalar fields are what matter here.
     */
    static Draft salvageDraftByRegex(String text) {
        String headline = regexStringField(text, "headline");
        if (headline == null || headline.isBlank()) return null;
        return new Draft(
                headline,
                orEmpty(regexStringField(text, "sentiment")),
                orEmpty(regexStringField(text, "highlight")),
                orEmpty(regexStringField(text, "trigger")),
                emptyToNull(orEmpty(regexStringField(text, "tickerSymbol"))),
                List.of(),
                regexNumberField(text, "priceMovePercent"),
                List.of(),
                emptyToNull(orEmpty(regexStringField(text, "assetClass"))),
                List.of(),
                List.of());
    }

    /** Extracts a {@code "key": "value"} string (quote/escape-aware) from possibly-broken JSON. */
    static String regexStringField(String text, String key) {
        if (text == null) return null;
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(text);
        return m.find() ? m.group(1).replace("\\\"", "\"").trim() : null;
    }

    /** Extracts a {@code "key": <number>} value from possibly-broken JSON. */
    static Double regexNumberField(String text, String key) {
        if (text == null) return null;
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").matcher(text);
        return m.find() ? Double.valueOf(m.group(1)) : null;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** True if the unit carries a resolved (Yahoo) live price — a "verified" figure source. */
    private static boolean unitHasVerifiedPrice(SubjectUnit unit) {
        MarketSnapshot s = unit == null ? null : unit.snapshot();
        return s != null && s.hasPrice();
    }

    /** A price-shaped number in the headline: a decimal, or a digit next to %/€/$/£. */
    private static final Pattern PRICE_LIKE =
            Pattern.compile("[-+]?\\d+[.,]\\d|\\d\\s*[%€$£]|[%€$£]\\s*\\d");

    private static boolean mentionsPrice(Draft draft) {
        if (draft == null) return false;
        return draft.priceMovePercent() != null || headlineHasPriceNumber(draft.headline());
    }

    /** A price-shaped figure in the text: a decimal, or a digit next to %/€/$/£ ("S&P 500" is not). */
    static boolean headlineHasPriceNumber(String headline) {
        return headline != null && PRICE_LIKE.matcher(headline).find();
    }

    // ---- model + JSON helpers ----

    private String chat(ChatModel model, String systemPrompt, String userMessage) {
        // Ollama TRUNCATES a prompt beyond num_ctx silently — the model then sees a
        // cut-off brief and produces exactly the confused output that looks like
        // sudden dumbness, with no error anywhere. Estimate (~4 chars/token) and
        // at least make the overflow visible. 512 tokens headroom for the reply.
        int estTokens = (systemPrompt.length() + userMessage.length()) / 4;
        int ctx = brain.contextTokens();
        if (estTokens > ctx - 512) {
            LOG.warn("[CTX] prompt ~{} tok vs num_ctx {} — Ollama will silently truncate; "
                    + "brief should have been budgeted tighter (sys={} chars, user={} chars)",
                    estTokens, ctx, systemPrompt.length(), userMessage.length());
        }
        List<ChatMessage> messages = List.of(
                SystemMessage.from(systemPrompt), UserMessage.from(userMessage));
        // Gate the actual model call through AgentBrain's SHARED gemma4 gate so prep
        // extraction + worker composition + vision together never exceed Ollama's
        // NUM_PARALLEL=2. Uninterruptible: a daemon worker shut down mid-acquire would
        // otherwise abandon a permit it never took.
        long t0 = System.nanoTime();
        brain.acquireLlm();
        long tAcq = System.nanoTime();
        try {
            ChatResponse response = model.chat(ChatRequest.builder().messages(messages).build());
            long t1 = System.nanoTime();
            AiMessage ai = response.aiMessage();
            // PROFILING: gate-wait (semaphore contention) vs gen (the model itself); in/out
            // token counts expose a JSON-mode whitespace-loop (out ≫ the ~80 a headline needs)
            // and a heavy prefill (in). Thread name (editorial-worker vs editorial-prep) tells
            // compose from extraction.
            var tu = response.tokenUsage();
            LOG.info("[LLM] gate-wait={}ms gen={}ms in={} out={}",
                    (tAcq - t0) / 1_000_000, (t1 - tAcq) / 1_000_000,
                    tu == null ? -1 : tu.inputTokenCount(), tu == null ? -1 : tu.outputTokenCount());
            return ai == null || ai.text() == null ? "" : ai.text();
        } finally {
            brain.releaseLlm();
        }
    }

    /**
     * Lenient JSON extraction — models wrap the object in ```json fences or
     * stray prose. Grabs the outermost {@code { ... }} and parses it.
     */
    private static JsonNode parseJson(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return JSON.readTree(text.substring(start, end + 1));
        } catch (Exception e) {
            LOG.debug("JSON parse failed: {}", e.getMessage());
            return null;
        }
    }

    private static List<String> readStrings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode el : node) {
                String s = el.asText("").trim();
                if (!s.isEmpty()) out.add(s);
            }
        }
        return out;
    }

    /**
     * The news sources INHERITED by a line that cites prior lines ("derivedFrom"
     * ordinals, 1-based within the story-memory window rendered by
     * {@link #appendStoryMemory}): each cited prior line's archived {@code newsRefs}
     * carry over. Out-of-range ordinals are the model mis-counting — skipped, never
     * fatal. Prior records are matched by exact headline text (the unit's story
     * memory and the repository both hold the published text verbatim).
     * Package-private for tests.
     */
    static List<de.bsommerfeld.wsbg.terminal.db.HeadlineNewsRef> inheritedRefs(
            List<SubjectUnit.UnitHeadline> priors, List<Integer> derivedFrom,
            List<HeadlineRecord> records) {
        if (priors == null || priors.isEmpty() || derivedFrom == null || derivedFrom.isEmpty()
                || records == null || records.isEmpty()) {
            return List.of();
        }
        int shownFrom = Math.max(0, priors.size() - PRIOR_HEADLINES_SHOWN);
        List<SubjectUnit.UnitHeadline> shown = priors.subList(shownFrom, priors.size());
        List<de.bsommerfeld.wsbg.terminal.db.HeadlineNewsRef> out = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        for (Integer ord : derivedFrom) {
            if (ord == null || ord < 1 || ord > shown.size()) continue;
            String citedText = shown.get(ord - 1).text();
            for (HeadlineRecord r : records) {
                if (!citedText.equals(r.headline())) continue;
                for (de.bsommerfeld.wsbg.terminal.db.HeadlineNewsRef ref : r.newsRefs()) {
                    if (ref.url() != null && seenUrls.add(ref.url())) out.add(ref);
                }
            }
        }
        return out;
    }

    /** Integers out of a JSON array node; non-numbers and anything else → skipped. */
    private static List<Integer> readInts(JsonNode node) {
        List<Integer> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode el : node) {
                if (el.canConvertToInt()) out.add(el.asInt());
            }
        }
        return out;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
