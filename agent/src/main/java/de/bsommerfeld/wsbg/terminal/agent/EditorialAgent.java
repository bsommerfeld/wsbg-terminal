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
import java.util.Set;

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
     * run in batches of this size and the names unioned — a long single brief made
     * the 4B model emit a malformed/truncated subjects array (or overrun num_ctx),
     * losing the whole thread. Smaller batches keep each output array short and
     * reliable; no comment is dropped (every batch is extracted, names deduped).
     */
    private static final int EXTRACT_CHUNK_SIZE = 25;

    private final AgentBrain brain;
    private final ClusterRegistry clusterRegistry;
    private final AgentRepository agentRepository;
    private final RedditRepository redditRepository;
    private final boolean newsCoverageEnabled; // #3b gate; default off (config)
    private final ReportBuilder reportBuilder;
    private final TickerResolver tickerResolver;
    private final HeadlineWriter headlineWriter;
    private final SubjectAttributor attributor;

    @Inject
    public EditorialAgent(AgentBrain brain, ClusterRegistry clusterRegistry,
            AgentRepository agentRepository,
            RedditRepository redditRepository,
            ApplicationEventBus eventBus, I18nService i18n,
            YahooFinanceClient yahooFinance, EmbeddingService embeddings, GlobalConfig config) {
        this.brain = brain;
        this.clusterRegistry = clusterRegistry;
        this.agentRepository = agentRepository;
        this.redditRepository = redditRepository;
        this.newsCoverageEnabled = config.getHeadlines().isNewsCoverageEnabled();
        this.reportBuilder = new ReportBuilder(redditRepository, brain);
        this.tickerResolver = new TickerResolver(yahooFinance, embeddings); // Tier 2 enabled
        this.headlineWriter = new HeadlineWriter(agentRepository, eventBus);
        this.attributor = new SubjectAttributor(redditRepository, brain);
    }

    /**
     * #2 step 1 — extracts + resolves the cluster's subjects (as the editorial
     * tick does) but, instead of composing, attributes each subject's evidence
     * into the feed-wide {@link SubjectRegistry}. Lets the {@code .lab} harness
     * show how subject units accumulate before per-unit composition exists.
     * Returns the resolved subjects for the trace.
     */
    public List<ResolvedSubject> attributeCluster(String clusterId, SubjectRegistry registry) {
        ChatModel model = brain.getAgentModel();
        InvestigationCluster cluster = clusterRegistry.getCluster(clusterId);
        if (model == null || cluster == null) return List.of();

        String brief = reportBuilder.buildReportData(cluster, agentRepository.getHeadlinesByClusterId(clusterId));
        Subjects subjects = extractSubjects(model, cluster, brief);
        int[] relatedAlloc = distributeRelated(subjects.names().size(), RELATED_BUDGET, RELATED_PER_SUBJECT);
        List<ResolvedSubject> resolved = tickerResolver.resolveAll(subjects.names(), relatedAlloc);
        attributor.attribute(registry, cluster, resolved);
        return resolved;
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
            return new UnitDraft("", "", null, false, false, "", 0, List.of(), false);
        }
        ChatModel model = brain.getAgentModel();
        if (model == null) {
            return new UnitDraft(unit.id, unit.canonicalName(), null, false, false, "", 0, List.of(), false);
        }
        String sys = PromptLoader.load("headline-compose-unit")
                .replace("{{LANGUAGE}}", brain.getUserLanguage().displayName())
                .replace("{{ROOM_SLANG}}", WsbgJargon.roomSlangForPrompt());
        String user = unitBrief(unit, newsCoverageEnabled);

        long t0 = System.nanoTime();
        String text = chat(model, sys, user);
        long elapsed = ms(t0, System.nanoTime());

        Draft draft = null;
        boolean isUpdate = false;
        boolean salvaged = false;
        List<String> citedNews = List.of();
        JsonNode obj = firstHeadlineObject(parseJson(text));
        if (obj != null) {
            draft = toDraft(obj);
            isUpdate = "UPDATE".equalsIgnoreCase(obj.path("mode").asText(""));
            citedNews = readStrings(obj.path("sourceNewsIds"));
        }
        if (draft == null) {
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
        if (draft == null) {
            // Even balanced-object salvage failed (a stray quote like "ticker": null"
            // breaks the object) — recover the headline + scalars by regex so the line
            // isn't lost.
            draft = salvageDraftByRegex(text);
            if (draft != null) {
                isUpdate = "UPDATE".equalsIgnoreCase(orEmpty(regexStringField(text, "mode")));
                salvaged = true;
            }
        }
        // A price/% in the line is UNVERIFIED when we have no resolved market data
        // for the subject — it then comes from the room's own post/screenshot, not
        // from Yahoo. The wire is a sentiment mirror; user numbers aren't facts.
        boolean unverified = mentionsPrice(draft) && !unitHasVerifiedPrice(unit);
        return new UnitDraft(unit.id, unit.canonicalName(), draft, isUpdate, salvaged, text, elapsed,
                citedNews, unverified);
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
    static final int EVIDENCE_CHAR_BUDGET = 6000;

    /** Builds the per-unit brief: Yahoo data + the room's evidence about this subject + its story memory. Static for testability. */
    static String unitBrief(SubjectUnit unit, boolean newsCoverageEnabled) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== SUBJECT: ").append(unit.canonicalName());
        if (unit.isInstrument()) sb.append(" (").append(unit.ticker()).append(")");
        sb.append(" ===\n");

        Instant now = Instant.now();
        MarketSnapshot s = unit.snapshot();
        if (s != null && s.hasPrice()) {
            sb.append(String.format(Locale.ROOT, "Yahoo market data: %.2f%s", s.price(),
                    s.currency() == null || s.currency().isEmpty() ? "" : " " + s.currency()));
            if (Double.isFinite(s.dayChangePercent())) {
                sb.append(String.format(Locale.ROOT, ", day %+.2f%%", s.dayChangePercent()));
            }
            // Price anchor: where the subject stood when the room first surfaced
            // it. Survives the evidence prune — the "since first mention" arc is
            // story memory, not a Reddit claim (both prices are Yahoo's own).
            Double anchor = unit.firstPrice();
            if (anchor != null && anchor > 0 && unit.firstPriceAt() != null) {
                double sinceFirst = (s.price() - anchor) / anchor * 100.0;
                sb.append(String.format(Locale.ROOT,
                        "; since first mention (%s ago): %+.2f%% (%.2f → %.2f)",
                        age(unit.firstPriceAt(), now), sinceFirst, anchor, s.price()));
            }
            sb.append('\n');
        } else if (!unit.isInstrument()) {
            sb.append("No ticker — theme/person; write from the room's sentiment, no ticker.\n");
        }

        // News not yet cited by a prior headline for THIS subject (covered ones are
        // filtered so two headlines never rest on the same item). Each carries a
        // [news:ID] the model echoes back in sourceNewsIds to mark it consumed.
        // Old items are kept (no fresh news is also a situation worth reporting
        // from) but tagged STALE so they're never sold as a fresh catalyst.
        List<RawNewsItem> freshNews = new ArrayList<>();
        for (RawNewsItem n : unit.news()) {
            // News coverage is OFF by default: news enriches freely and may back
            // several headlines on a topic (it's cached, so reuse is free). Only when
            // explicitly enabled do we hide a unit's already-cited news.
            if (!newsCoverageEnabled || !unit.isNewsCovered(n.uuid())) freshNews.add(n);
        }
        if (!freshNews.isEmpty()) {
            sb.append("News (context & attribution — NOT the subject; cite any you lean on by its"
                    + " [news:ID] in sourceNewsIds, a cited item won't be offered again."
                    + " [STALE] = older background, NOT a fresh catalyst):\n");
            for (RawNewsItem n : freshNews) {
                sb.append("  - [news:").append(n.uuid() == null || n.uuid().isBlank() ? "?" : n.uuid()).append("] ");
                if (n.publishedAt() != null) {
                    sb.append(age(n.publishedAt(), now)).append(" ago ");
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

        // Evidence under a char budget: keep the NEWEST refs that fit, drop the
        // oldest, and say so — never let Ollama truncate the prompt silently.
        List<SubjectUnit.EvidenceRef> refs = unit.evidence();
        int start = refs.size();
        int budget = EVIDENCE_CHAR_BUDGET;
        while (start > 0 && budget - refs.get(start - 1).snippet().length() - 24 >= 0) {
            start--;
            budget -= refs.get(start).snippet().length() + 24;
        }
        sb.append("\nWhat the room said about THIS subject (evidence — the story):\n");
        if (start > 0) {
            sb.append("  (").append(start).append(" earlier mention(s) omitted — the story is older"
                    + " than shown; prior headlines below already reflect them)\n");
        }
        List<SubjectUnit.EvidenceRef> context = new ArrayList<>();
        for (SubjectUnit.EvidenceRef e : refs.subList(start, refs.size())) {
            if ("reddit-context".equals(e.source())) {
                context.add(e); // a reply chain this subject was named in — rendered below
                continue;
            }
            String loc = "vision".equals(e.source()) ? "image"
                    : (e.commentId() == null ? e.threadId() : e.commentId());
            sb.append("  - [").append(loc).append(", ")
                    .append(age(Instant.ofEpochSecond(e.addedAtEpoch()), now)).append(" ago] ")
                    .append(e.snippet()).append('\n');
        }
        if (!context.isEmpty()) {
            sb.append("Conversation those mentions were a reply to (context — NOT the subject "
                    + "itself, but the thread of discussion it was named in):\n");
            for (SubjectUnit.EvidenceRef e : context) {
                sb.append("    ↳ ").append(e.snippet()).append('\n');
            }
        }

        appendStoryMemory(sb, unit.headlines(), now);
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
            Instant now) {
        if (prior.isEmpty()) return;
        sb.append("\nHEADLINES ALREADY PUBLISHED FOR THIS SUBJECT (story memory — classify NEW vs"
                + " UPDATE against ALL of these; never repeat verbatim):\n");
        int shownFrom = Math.max(0, prior.size() - PRIOR_HEADLINES_SHOWN);
        if (shownFrom > 0) {
            SubjectUnit.UnitHeadline first = prior.get(0);
            sb.append("  (+").append(shownFrom).append(" earlier headline(s) since ")
                    .append(age(Instant.ofEpochSecond(first.atEpoch()), now))
                    .append(" ago — the story is OLDER than the lines shown; do NOT re-open an"
                            + " already-covered angle as NEW)\n");
        }
        for (SubjectUnit.UnitHeadline h : prior.subList(shownFrom, prior.size())) {
            sb.append("  - [").append(age(Instant.ofEpochSecond(h.atEpoch()), now)).append(" ago");
            if (h.sentiment() != null && !h.sentiment().isBlank()) sb.append(", ").append(h.sentiment());
            sb.append("] ").append(h.text()).append('\n');
        }
        String arc = sentimentArc(prior);
        if (!arc.isEmpty()) sb.append("Sentiment arc so far: ").append(arc).append('\n');
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
            boolean salvaged, String raw, long ms, List<String> citedNewsIds, boolean unverified) {}

    /**
     * Runs one editorial tick over the given dirty clusters. Each cluster is
     * processed independently through the pipeline; one failing cluster never
     * blocks the rest.
     */
    public void runTick(Set<String> dirtyClusterIds) {
        if (dirtyClusterIds == null || dirtyClusterIds.isEmpty()) return;
        ChatModel model = brain.getAgentModel();
        if (model == null) {
            LOG.warn("EditorialAgent: agent model not ready, skipping tick.");
            return;
        }
        int published = 0;
        for (String id : dirtyClusterIds) {
            try {
                published += processCluster(model, id, EditorialListener.NO_OP).published();
            } catch (Exception e) {
                LOG.warn("EditorialAgent: cluster {} failed: {}", id, e.getMessage());
            }
        }
        LOG.info("[AGENT] tick done: {} cluster(s) → {} headline(s)", dirtyClusterIds.size(), published);
    }

    /**
     * Runs the full editorial pipeline for a single cluster and returns every
     * intermediate artifact (extracted subjects, resolved Yahoo data, composed
     * drafts) alongside the published count. The {@code editorial-lab} harness
     * calls this to render the pipeline step-by-step; {@link #runTick} uses the
     * same path and only reads {@link ClusterEditorial#published()}.
     *
     * <p>Side effects are identical to a live tick: accepted drafts are persisted
     * to {@link AgentRepository} and broadcast by {@link HeadlineWriter}.
     */
    public ClusterEditorial runClusterTraced(String clusterId) {
        return runClusterTraced(clusterId, EditorialListener.NO_OP);
    }

    /**
     * Same as {@link #runClusterTraced(String)} but fires {@code listener}
     * callbacks as each stage finishes — so the {@code .lab} harness can render
     * the trace <em>live</em> (subjects, then resolution, then each headline as
     * it is composed) instead of waiting for the whole cluster to finish.
     */
    public ClusterEditorial runClusterTraced(String clusterId, EditorialListener listener) {
        ChatModel model = brain.getAgentModel();
        if (model == null) {
            return ClusterEditorial.empty(clusterId);
        }
        return processCluster(model, clusterId, listener == null ? EditorialListener.NO_OP : listener);
    }

    /** Streaming hook for the harness — every method has a no-op default. */
    public interface EditorialListener {
        EditorialListener NO_OP = new EditorialListener() {};
        default void onSubjects(List<String> names, String raw, long ms) {}
        default void onResolved(List<ResolvedSubject> resolved, long ms) {}
        default void onSubjectDraft(SubjectDraft draft) {}
    }

    private ClusterEditorial processCluster(ChatModel model, String clusterId, EditorialListener listener) {
        InvestigationCluster cluster = clusterRegistry.getCluster(clusterId);
        if (cluster == null) {
            return ClusterEditorial.empty(clusterId);
        }

        List<HeadlineRecord> priorHeadlines = agentRepository.getHeadlinesByClusterId(clusterId);
        String brief = reportBuilder.buildReportData(cluster, priorHeadlines);

        // Stage 1 — subjects (slang-normalised for lookup). Uncapped.
        long t0 = System.nanoTime();
        Subjects subjects = extractSubjects(model, cluster, brief);
        long t1 = System.nanoTime();
        List<String> names = subjects.names();
        listener.onSubjects(names, subjects.raw(), ms(t0, t1));

        // Stage 2 — deterministic resolution, BATCHED. The related second-hop is
        // a shared budget spread evenly across all subjects; every price snapshot
        // (own ticker + related) is fetched in ONE spark call (see resolveAll).
        int[] relatedAlloc = distributeRelated(names.size(), RELATED_BUDGET, RELATED_PER_SUBJECT);
        List<ResolvedSubject> resolved = tickerResolver.resolveAll(names, relatedAlloc);
        long t2 = System.nanoTime();
        listener.onResolved(resolved, ms(t1, t2));

        // Stage 3+4 — ONE focused compose call PER subject, and each headline is
        // QA'd + published the moment it's composed (NOT held until all subjects
        // are done). A small model handles a single, focused headline reliably
        // but degenerates / drops to prose when asked for a big JSON array — so
        // we never batch. Publishing inline means the wire fills one line at a
        // time as the desk works through the subjects, instead of going silent
        // for the whole cluster and then dumping every line at once. A cluster
        // with no market subject still gets one general-sentiment line.
        List<ResolvedSubject> targets = resolved.isEmpty()
                ? java.util.Collections.singletonList((ResolvedSubject) null) : resolved;
        List<SubjectDraft> subjectDrafts = new ArrayList<>();
        int n = 0;
        for (ResolvedSubject rs : targets) {
            SubjectDraft sd = composeOne(model, brief, rs);
            if (sd.draft() != null && headlineWriter.publish(cluster, sd.draft(), resolved)) n++;
            subjectDrafts.add(sd);
            listener.onSubjectDraft(sd);
        }
        long t3 = System.nanoTime();

        return new ClusterEditorial(clusterId, cluster.initialTitle, names,
                subjects.namedByModel(), subjects.raw(), resolved, subjectDrafts, n,
                ms(t0, t1), ms(t1, t2), ms(t2, t3));
    }

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

    /**
     * Every artifact one cluster's editorial pass produced. {@code subjects} is
     * the stage-1 output (uncapped); {@code subjectsNamedByModel} is how many the
     * model named (equals {@code subjects.size()} now that there's no cap);
     * {@code subjectsRaw} is the stage-1 raw reply for diagnosis;
     * {@code subjectDrafts} carries one entry per focused compose call (per
     * subject, or one "general" entry for a subject-less cluster), each with its
     * draft (or null), raw reply, salvage flag and latency; {@code published} is
     * how many survived {@link HeadlineWriter}'s QA.
     */
    public record ClusterEditorial(
            String clusterId,
            String initialTitle,
            List<String> subjects,
            int subjectsNamedByModel,
            String subjectsRaw,
            List<ResolvedSubject> resolved,
            List<SubjectDraft> subjectDrafts,
            int published,
            long subjectsMs,
            long resolveMs,
            long composeMs) {

        static ClusterEditorial empty(String clusterId) {
            return new ClusterEditorial(clusterId, null, List.of(), 0, "", List.of(), List.of(),
                    0, 0, 0, 0);
        }
    }

    /**
     * One focused per-subject compose call's result: the subject label, the draft
     * it produced (or {@code null} when the model chose no headline or the reply
     * was unparseable), whether the draft had to be salvaged from malformed JSON,
     * the raw model reply (for the lab), and the call latency in ms.
     */
    public record SubjectDraft(String label, Draft draft, boolean salvaged, String raw, long ms) {}

    // ---- Stage 1: subject extraction ----

    private Subjects extractSubjects(ChatModel model, InvestigationCluster cluster, String brief) {
        String sys = PromptLoader.load("subject-extraction")
                .replace("{{ENTITY_ALIASES}}", WsbgJargon.entityAliasesForPrompt());

        int comments = countComments(cluster);
        if (comments <= EXTRACT_CHUNK_SIZE) {
            // Common path, unchanged: one call over the full rich brief (vision,
            // poll, covered-split all intact).
            String text = chat(model, sys, brief);
            List<String> out = dedupClean(parseSubjectNames(text));
            if (out.isEmpty()) {
                String raw = text == null ? "" : text.strip();
                LOG.warn("[EXTRACT] 0 subjects — brief={} chars (~{} tok), system={} chars; raw reply: {}",
                        brief.length(), brief.length() / 4, sys.length(),
                        raw.length() > 400 ? raw.substring(0, 400) + "…" : raw);
            }
            return new Subjects(out, out.size(), text);
        }

        // Many comments → batch the extraction so each output array stays short
        // and reliable, then union the names. No comment is dropped.
        List<String> chunks = commentChunks(cluster, EXTRACT_CHUNK_SIZE);
        Map<String, String> union = new LinkedHashMap<>(); // lower-case key → first-seen spelling
        for (String chunk : chunks) {
            String text = chat(model, sys, chunk);
            for (String name : parseSubjectNames(text)) {
                String clean = cleanSubjectName(name);
                if (!clean.isEmpty()) union.putIfAbsent(clean.toLowerCase(Locale.ROOT), clean);
            }
        }
        LOG.info("[EXTRACT] chunked {} comments into {} batch(es) → {} unique subject(s)",
                comments, chunks.size(), union.size());
        List<String> names = new ArrayList<>(union.values());
        return new Subjects(names, names.size(), "<chunked: " + chunks.size() + " batch(es)>");
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
    static String cleanSubjectName(String name) {
        if (name == null) return "";
        String cut = PRICE_TAIL.matcher(name.strip()).replaceFirst("").strip();
        return cut.isEmpty() ? name.strip() : cut;
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

    /** Strict parse of the subjects array, with a salvage pass for a broken/truncated reply. */
    private List<String> parseSubjectNames(String text) {
        List<String> out = new ArrayList<>();
        JsonNode root = parseJson(text);
        if (root != null && root.path("subjects").isArray()) {
            for (JsonNode s : root.path("subjects")) {
                String name = s.asText("").trim();
                if (!name.isEmpty()) out.add(name);
            }
        }
        if (out.isEmpty()) {
            // The 4B model occasionally emits a malformed/truncated subjects array
            // (long arrays are where it degenerates) → recover whatever names came
            // through intact rather than losing the whole batch.
            List<String> salvaged = salvageSubjectNames(text);
            if (!salvaged.isEmpty()) {
                LOG.warn("[EXTRACT] strict parse failed, salvaged {} subject name(s)", salvaged.size());
                out.addAll(salvaged);
            }
        }
        return out;
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
        for (int i = 0; i < lines.size(); i += perChunk) {
            StringBuilder sb = new StringBuilder(preamble);
            sb.append("\nCOMMENTS (batch ").append(chunks.size() + 1).append("):\n");
            for (int j = i; j < Math.min(i + perChunk, lines.size()); j++) {
                sb.append(lines.get(j)).append('\n');
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

    /** Stage-1 output: the named subjects (uncapped), their count, and the raw reply. */
    private record Subjects(List<String> names, int namedByModel, String raw) {}

    /**
     * Recovers subject names from a reply whose JSON is broken/truncated: finds the
     * {@code "subjects"} key, takes the array body (to the closing {@code ]} or — if
     * the reply was cut off — to the end), and pulls every complete quoted string.
     * A truncated final entry (no closing quote) is simply skipped, so we keep
     * whatever names came through intact rather than losing all of them.
     * Package-private for testing.
     */
    static List<String> salvageSubjectNames(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        int key = text.indexOf("\"subjects\"");
        if (key < 0) return out;
        int lb = text.indexOf('[', key);
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

    // ---- Stage 3: headline composition (one focused call per subject) ----

    /**
     * Composes ONE headline for a single subject (or, when {@code subject} is
     * null, for the cluster's overall sentiment). Small, focused prompt + a
     * single-object output → the 4B model stays coherent and never has to emit a
     * long JSON array. Parsing is forgiving: a single object, a {@code {"headlines":[…]}}
     * wrapper the model sometimes adds, or — failing both — the first balanced
     * {@code {...}} salvaged from the reply.
     */
    private SubjectDraft composeOne(ChatModel model, String brief, ResolvedSubject subject) {
        String label = subject == null ? "(cluster general sentiment)" : subject.canonicalName();
        String sys = PromptLoader.load("headline-compose-single")
                .replace("{{LANGUAGE}}", brain.getUserLanguage().displayName())
                .replace("{{ROOM_SLANG}}", WsbgJargon.roomSlangForPrompt());
        String subjectBlock = subject == null
                ? "(no specific instrument — read the cluster's overall sentiment and write one line for it, without a ticker)\n"
                : renderOne(subject);
        String user = brief
                + "\n\n=== THIS SUBJECT (Yahoo Finance — NOT Reddit; context & attribution only) ===\n"
                + subjectBlock;

        long t0 = System.nanoTime();
        String text = chat(model, sys, user);
        long elapsed = ms(t0, System.nanoTime());

        Draft d = null;
        JsonNode root = parseJson(text);
        if (root != null) {
            if (root.has("headline")) {
                d = toDraft(root);
            } else if (root.path("headlines").isArray() && !root.path("headlines").isEmpty()) {
                d = toDraft(root.path("headlines").get(0));
            }
        }
        boolean salvaged = false;
        if (d == null) {
            for (JsonNode obj : salvageObjects(text)) {
                d = toDraft(obj);
                if (d != null) { salvaged = true; break; }
            }
        }
        if (d == null) {
            d = salvageDraftByRegex(text); // stray-quote-broken JSON → recover by regex
            if (d != null) salvaged = true;
        }
        return new SubjectDraft(label, d, salvaged, text, elapsed);
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

    /** Renders resolved subjects into the data block the compose stage reads. */
    /** Renders ONE resolved subject's Yahoo block for the per-subject compose prompt. */
    private static String renderOne(ResolvedSubject r) {
        StringBuilder sb = new StringBuilder();
        Instant now = Instant.now();
        sb.append("- ").append(r.canonicalName());
        if (r.isInstrument()) {
            sb.append(" → ticker ").append(r.ticker());
            MarketSnapshot s = r.snapshot();
            if (s != null && s.hasPrice()) {
                sb.append(String.format(Locale.ROOT, " | price %.2f%s", s.price(),
                        s.currency() == null || s.currency().isEmpty() ? "" : " " + s.currency()));
                if (Double.isFinite(s.dayChangePercent())) {
                    sb.append(String.format(Locale.ROOT, ", day %+.2f%%", s.dayChangePercent()));
                }
            }
        } else {
            sb.append(" → no ticker (theme/person — news only, write without a ticker)");
        }
        sb.append('\n');
        for (RawNewsItem n : r.news()) {
            sb.append("    Yahoo news: ");
            appendNewsAge(sb, n, now);
            sb.append(n.title());
            if (n.publisher() != null && !n.publisher().isEmpty()) sb.append(" · ").append(n.publisher());
            sb.append('\n');
        }
        // Second-hop evidence: instruments this subject's news is about, with
        // their live move. Raw material for a causal read — the desk links them
        // only if it genuinely holds; never forced.
        for (TickerResolver.RelatedInstrument ri : r.related()) {
            MarketSnapshot s = ri.snapshot();
            sb.append("    related instrument (from the news above): ").append(ri.ticker());
            if (s != null && s.hasPrice() && Double.isFinite(s.dayChangePercent())) {
                sb.append(String.format(Locale.ROOT, " %+.2f%% today", s.dayChangePercent()));
            }
            sb.append('\n');
            for (RawNewsItem n : ri.news()) {
                sb.append("        Yahoo news: ");
                appendNewsAge(sb, n, now);
                sb.append(n.title());
                if (n.publisher() != null && !n.publisher().isEmpty()) sb.append(" · ").append(n.publisher());
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** Appends "3h ago — " (with a {@code [STALE]} tag past {@link #NEWS_STALE_AFTER}) when the item is dated. */
    private static void appendNewsAge(StringBuilder sb, RawNewsItem n, Instant now) {
        if (n.publishedAt() == null) return;
        sb.append(age(n.publishedAt(), now)).append(" ago ");
        if (Duration.between(n.publishedAt(), now).compareTo(NEWS_STALE_AFTER) > 0) {
            sb.append("[STALE] ");
        }
        sb.append("— ");
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
        ChatResponse response = model.chat(ChatRequest.builder().messages(messages).build());
        AiMessage ai = response.aiMessage();
        return ai == null || ai.text() == null ? "" : ai.text();
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

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
