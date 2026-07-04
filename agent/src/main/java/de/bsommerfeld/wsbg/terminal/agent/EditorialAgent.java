package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.HeadlineWriter.Draft;
import de.bsommerfeld.wsbg.terminal.agent.TickerResolver.ResolvedSubject;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.i18n.I18nService;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.db.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns the dirty clusters of one editorial tick into published headlines via a
 * fixed, deterministic pipeline — no free tool loop, no round cap.
 *
 * <p>Per cluster, two model calls with deterministic glue between them:
 * <ol>
 *   <li><b>Subject extraction</b> (model, {@link SubjectExtractor}): the cluster
 *       brief in, a list of market-relevant subject names out (slang normalised to
 *       canonical/English via {@link WsbgJargon}).</li>
 *   <li><b>Resolve</b> (code, {@link TickerResolver}): each subject → validated
 *       Yahoo ticker + live market data + news, or news-only, or nothing.</li>
 *   <li><b>Headline composition</b> (model): the brief ({@link UnitBriefWriter}) +
 *       the resolved data in, a structured headline draft out
 *       ({@link ComposeReplyParser}).</li>
 *   <li><b>Write</b> (code, {@link HeadlineWriter}): QA + persist + broadcast.</li>
 * </ol>
 *
 * <p>The self-contained stages live in dedicated collaborators (all hand-built in the
 * constructor): {@link ChatGateway} (the semaphore-gated model call), {@link Gemma4Judge}
 * (the two discrete judge calls), {@link SubjectExtractor}, {@link ComposeReplyParser},
 * {@link NewsProvenance} and {@link UnitBriefWriter}. This class is the orchestration
 * that wires them into the prep/attribute/compose-publish stages.
 */
@Singleton
public class EditorialAgent {

    private static final Logger LOG = LoggerFactory.getLogger(EditorialAgent.class);

    /**
     * Subjects are NOT capped — the wire mirrors the room 1:1, so every named
     * subject gets its own focused compose call. The expensive second-hop
     * (related instruments) is instead a shared budget spread evenly across ALL
     * subjects ({@link SubjectExtractor#distributeRelated}, round-robin), so a
     * 22-subject cluster gives each subject ~1 related instead of loading the
     * first 6 with 4 each. {@code RELATED_BUDGET} = total related lookups per
     * cluster (= the old 6×4); {@code RELATED_PER_SUBJECT} = cap any single
     * subject can take.
     */
    private static final int RELATED_BUDGET = 24;
    private static final int RELATED_PER_SUBJECT = 4;

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
    /** The ONE shared gemma4 concurrency gate (NUM_PARALLEL=2) — passed to {@link ChatGateway}. */
    private final LlmGate llmGate;
    private final ClusterRegistry clusterRegistry;
    private final AgentRepository agentRepository;
    private final RedditRepository redditRepository;
    private final ReportBuilder reportBuilder;
    private final TickerResolver tickerResolver;
    private final HeadlineWriter headlineWriter;
    private final SubjectAttributor attributor;
    /** Feed-wide subject store — the editorial atom in prod after the #2 cutover. */
    private final SubjectRegistry subjectRegistry;
    /** The single semaphore-gated model-call seam (NUM_PARALLEL=2), shared by every stage below. */
    private final ChatGateway chatGateway;
    /** The two discrete gemma4 judge calls (tier-2 instrument match, same-story dup). */
    private final Gemma4Judge gemma4Judge;
    /** Stage 1 — subject extraction (brief + cluster → names + model primary). */
    private final SubjectExtractor subjectExtractor;
    /**
     * Per-unit count of consecutive compose whiffs (cleared on a publish, a
     * redundant-empty, or once the unit is parked). Bounds {@link #MAX_COMPOSE_RETRIES}.
     * Concurrent: under the #3 pipeline {@code composeAndPublishUnit} runs on several
     * compose worker threads (for DIFFERENT units), and {@code merge}'s atomic ops here
     * must stay race-free.
     */
    private final Map<String, Integer> composeRetries = new ConcurrentHashMap<>();

    /**
     * Free gemma4 permits right now — for the pipeline's contention logging. The gate is the
     * shared {@link LlmGate} {@code @Singleton} (the same instance the vision prefetch acquires,
     * since both hit the one model); prep extraction + compose + vision together never exceed
     * Ollama's NUM_PARALLEL=2.
     */
    public int availableLlmPermits() {
        return llmGate.availablePermits();
    }

    /**
     * Live config — read fresh each tick, NOT cached in the ctor, so the Settings
     * view (SettingsBridge mutates this same instance) takes effect without a
     * restart: cluster-theme mode (ALLES/NUR TICKER), news-coverage, and the
     * context-relief window all switch live.
     */
    private final GlobalConfig config;

    @Inject
    public EditorialAgent(AgentBrain brain, LlmGate llmGate, ClusterRegistry clusterRegistry,
            AgentRepository agentRepository,
            RedditRepository redditRepository,
            ApplicationEventBus eventBus, I18nService i18n,
            YahooFinanceClient yahooFinance,
            SubjectRegistry subjectRegistry, GlobalConfig config) {
        this.brain = brain;
        this.llmGate = llmGate;
        this.clusterRegistry = clusterRegistry;
        this.agentRepository = agentRepository;
        this.redditRepository = redditRepository;
        this.reportBuilder = new ReportBuilder(redditRepository, brain);
        this.tickerResolver = new TickerResolver(yahooFinance);
        this.chatGateway = new ChatGateway(brain, llmGate);
        this.gemma4Judge = new Gemma4Judge(brain, chatGateway);
        this.tickerResolver.setMatchJudge(gemma4Judge::matchInstrument); // Tier 2 enabled
        this.headlineWriter = new HeadlineWriter(agentRepository, eventBus);
        this.attributor = new SubjectAttributor(redditRepository, brain);
        this.subjectExtractor = new SubjectExtractor(brain, redditRepository, chatGateway);
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
     * Installs the tier-3 local instrument corpus onto the resolver. Optional
     * Guice method-injection: present in production (AppModule provides it),
     * absent in tests, where tier 3 simply stays off.
     */
    @com.google.inject.Inject(optional = true)
    void setInstrumentCorpus(de.bsommerfeld.wsbg.terminal.instruments.InstrumentCorpus corpus) {
        tickerResolver.setInstrumentCorpus(corpus);
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
     * into the feed-wide {@link SubjectRegistry} (the injected singleton).
     * Returns the resolved subjects for the trace.
     */
    public List<ResolvedSubject> attributeCluster(String clusterId) {
        List<ResolvedSubject> resolved = resolveClusterSubjects(clusterId);
        attributeResolved(clusterId, resolved);
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
        SubjectExtractor.Subjects subjects = subjectExtractor.extract(model, cluster, brief);
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
        int[] relatedAlloc = SubjectExtractor.distributeRelated(
                subjects.names().size(), RELATED_BUDGET, RELATED_PER_SUBJECT);
        // The thread title is the identity judge's context: it tells „Kakao" (the
        // commodity talk) apart from Kakao Corp when both spell the same.
        return tickerResolver.resolveAll(subjects.names(), relatedAlloc, cluster.initialTitle);
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
    public void attributeResolved(String clusterId, List<ResolvedSubject> resolved) {
        if (resolved == null) return;
        InvestigationCluster cluster = clusterRegistry.getCluster(clusterId);
        if (cluster == null) return;
        attributor.attribute(subjectRegistry, cluster, resolved, primaryHints.remove(clusterId));
    }

    /**
     * #2 step 3 — composes ONE headline for a single {@link SubjectUnit} from its
     * accumulated Reddit evidence + Yahoo data + its OWN prior headlines (story
     * continuity). The unit is the editorial atom now, not the cluster. Returns a
     * {@link UnitDraft}; storing the result on the unit / publishing it is the
     * caller's job.
     */
    public UnitDraft composeUnit(SubjectUnit unit) {
        if (unit == null) {
            return new UnitDraft("", "", null, false, "", 0, List.of(), false, false, List.of());
        }
        ChatModel model = brain.getComposeModel(); // tight numPredict — one short headline JSON
        if (model == null) {
            return new UnitDraft(unit.id, unit.canonicalName(), null, false, "", 0, List.of(), false, false, List.of());
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
        String user = UnitBriefWriter.unitBrief(unit, config.getHeadlines().isNewsCoverageEnabled(), BriefLabels.of(lang));

        boolean hasPriors = !unit.headlines().isEmpty();
        long t0 = System.nanoTime();
        String text = null;
        Draft draft = null;
        boolean salvaged = false;
        boolean redundant = false;
        List<Integer> citedNews = List.of();
        List<Integer> derivedFrom = List.of();
        // 1:1 mirror: a FIRST line is NEVER dropped. The room talking about a subject IS the
        // story (we attach price + news ourselves), so the only legitimate {"headline":""} is a
        // redundant UPDATE against the unit's OWN priors. The 4B model sometimes lazily returns
        // empty on a thin/question thread even with no priors — retry (its seed varies) before
        // accepting an empty/garbage first compose.
        for (int attempt = 0; ; attempt++) {
            text = chatGateway.chat(model, sys, user);
            ComposeReplyParser.ParsedCompose pc = ComposeReplyParser.parse(text, hasPriors);
            draft = pc.draft();
            salvaged = pc.salvaged();
            redundant = pc.redundant();
            citedNews = pc.newsUsed();
            derivedFrom = pc.derivedFrom();
            // Retry an empty/garbage reply only when we must NOT drop it: a first line
            // (no priors). A usable line, a legit redundant, or priors → stop.
            if (draft != null || redundant || hasPriors
                    || attempt >= FIRST_COMPOSE_EMPTY_RETRIES) break;
            LOG.info("[COMPOSE] empty line for {} ({}) — retry {}/{} (a first line is never dropped)",
                    unit.id, unit.canonicalName(), attempt + 1, FIRST_COMPOSE_EMPTY_RETRIES);
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
        boolean unverified = ComposeReplyParser.mentionsPrice(draft)
                && !ComposeReplyParser.unitHasVerifiedPrice(unit);
        return new UnitDraft(unit.id, unit.canonicalName(), draft, salvaged, text, elapsed,
                citedNews, unverified, whiffed, derivedFrom);
    }

    /**
     * One per-unit compose result (#2 step 3). {@code draft} is null when the model
     * wrote no usable headline. {@code newsUsed} = the 1-based brief ordinals of the
     * news items the model says the line leaned on (echoed from the numbered news
     * list, like {@code derivedFrom} for prior headlines). {@code unverified} = the
     * line carries a price/% that did NOT come from our data sources (no resolved
     * Yahoo data) — it's a user-posted number, to be shown with an "unverified"
     * marker, never as fact.
     */
    public record UnitDraft(String unitId, String label, Draft draft,
            boolean salvaged, String raw, long ms, List<Integer> newsUsed, boolean unverified,
            boolean whiffed, List<Integer> derivedFrom) {}

    /**
     * The production editorial tick. One dirty signal (a cluster that gained fresh
     * content) drives the feed-wide <b>{@link SubjectUnit}</b> producer — one
     * headline per dirty unit (a subject tracked across the whole feed), keyed by
     * unit id. The cluster itself is pure context for its subjects.
     *
     * Steps (on the singleton {@link SubjectRegistry}):
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

        // 2 — per dirty cluster, attribute its subjects into the feed-wide registry
        //     (marks units dirty for step 4). The cluster is pure context for its
        //     subjects — it produces no line of its own.
        for (String id : dirtyClusterIds) {
            try {
                attributeCluster(id);
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
        LOG.info("[AGENT] tick done: {} cluster(s) → {} unit headline(s) ({} dirty unit(s))",
                dirtyClusterIds.size(), published, toCompose.size());
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
        // Semantic near-duplicate guard: the 4B model sometimes re-words an
        // already-published line as a fresh NEW instead of returning the
        // redundant-empty (live: two "Absturz trifft die überbewerteten KI-Giganten
        // wie Nvidia" lines ~70 s apart) — token-Jaccard misses the paraphrase, the
        // gemma same-story verdict doesn't. Always on (the former strict-1:1 user
        // toggle was removed 2026-07-03).
        if (gemma4Judge.isSameStoryRepeat(d.headline(), u.headlines())) {
            LOG.info("[COMPOSE] semantic near-duplicate for unit {} — dropped (same story re-worded)", u.id);
            composeRetries.remove(u.id);
            u.markComposedAt(composedV);
            return false;
        }
        // News provenance is LINE-scoped — earned only by USE. The tag and its
        // clickable source list promise "the articles this line leans on"; the old
        // unit-scoped flag (!u.news().isEmpty()) lit the tag on EVERY line of a
        // news-rich subject, even a pure room-sentiment line that used none of it
        // (live: a Microsoft chart-waiting line carrying a pool of unrelated articles).
        // Union of two provenance signals: the deterministic token test (below) AND the
        // model's own newsUsed ordinals (echoed from the brief's [N#] list — the same
        // small-integer mechanism as derivedFrom). The token test alone is language-blind:
        // a German line fully paraphrasing an English item shares zero tokens (live: the
        // SAP cost-cuts line carried no tag despite being written FROM the news), so the
        // model's citation closes that gap while the token test backstops its under-citing.
        java.util.LinkedHashSet<RawNewsItem> usedSet = new java.util.LinkedHashSet<>();
        List<RawNewsItem> shown = NewsProvenance.briefNews(u, config.getHeadlines().isNewsCoverageEnabled());
        for (Integer ord : ud.newsUsed()) {
            if (ord != null && ord >= 1 && ord <= shown.size()) usedSet.add(shown.get(ord - 1));
        }
        for (RawNewsItem n : u.news()) {
            if (NewsProvenance.headlineReflectsNews(d.headline(), n)) usedSet.add(n);
        }
        List<RawNewsItem> newsUsed = List.copyOf(usedSet);
        // Provenance chaining: the model cites the numbered prior lines it built on
        // ("derivedFrom": [2]) — those lines' news sources carry over, so a fact that
        // debuted on an earlier, tagged line keeps its sources on every continuation
        // (without this, paraphrase through the story memory laundered provenance away).
        List<de.bsommerfeld.wsbg.terminal.db.HeadlineNewsRef> inherited =
                NewsProvenance.inheritedRefs(u.headlines(), ud.derivedFrom(),
                        agentRepository.getHeadlinesByClusterId(u.id), d.headline());
        u.markComposedAt(composedV);
        if (headlineWriter.publishUnit(u, d, newsUsed, inherited)) {
            composeRetries.remove(u.id); // story moved on → fresh retry budget
            u.addHeadline(d.headline(), d.sentiment());
            // Coverage marking rides the same woven-in list: a sentiment-only line
            // leaves its news fresh for the next compose (the next line orients on
            // prior headlines instead of re-milking the same item); another unit
            // pulling the same item is untouched (covered ids live on the unit).
            u.markNewsCovered(newsUsed.stream()
                    .map(RawNewsItem::uuid)
                    .filter(Objects::nonNull).toList());
            return true;
        }
        return false;
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

    /** Elapsed milliseconds between two {@link System#nanoTime()} reads. */
    private static long ms(long startNanos, long endNanos) {
        return (endNanos - startNanos) / 1_000_000L;
    }
}
