package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.event.DeepDiveFinishedEvent;
import de.bsommerfeld.wsbg.terminal.agent.event.DeepDiveProgressEvent;
import de.bsommerfeld.wsbg.terminal.agent.event.DeepDiveStartedEvent;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.price.AnalystView;
import de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDive;
import de.bsommerfeld.wsbg.terminal.core.price.InsiderDealings;
import de.bsommerfeld.wsbg.terminal.core.price.ShortInterest;
import de.bsommerfeld.wsbg.terminal.db.DeepDiveArchive;
import de.bsommerfeld.wsbg.terminal.db.DeepDiveRecord;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The KI-DD tool: on demand, ONE comprehensive due-diligence report for ONE
 * subject — a FIXED, dated snapshot (unlike the watchlist's continuously revised
 * dossier), archived permanently and exportable. Pulls EVERY data leg the
 * terminal has at generation time: identity via the pipeline's own resolver,
 * price (L&S), venue depth (Tradegate), profile facts (onvista), the deep
 * company record incl. official website, key-figure estimates, balance sheets,
 * boards, chart-technical read and peers (Consorsbank), analyst opinions +
 * corporate events (Consorsbank), insider dealings (BaFin), disclosed short
 * positions (Bundesanzeiger), triangulated news (Yahoo + WSO + Google News) —
 * plus the room's evidence from the feed-wide {@link SubjectRegistry}.
 *
 * <p><b>The report is written ITERATIVELY (user mandate 2026-07-13):</b> a
 * draft pass lays the full seven-section skeleton from the first packet; the
 * integrate passes work the remaining packets in one at a time; an adversarial
 * Q&amp;A pass interrogates the assembled report against a condensed FACT
 * SHEET of the verified material; a final pass works the review in and smooths
 * the red thread. All model calls ride the shared {@link LlmGate} on the roomy
 * {@code deepDiveModel} — on-demand only, so the fat token budget never
 * competes with the wire.
 *
 * <p><b>Loss-free is enforced MECHANICALLY, not just prompted (user mandate
 * 2026-07-13 "streng iterativ ohne Informationsverlust"):</b> the integrate and
 * final passes emit EDIT OPERATIONS ({@link EditScript}: replace / insert-after
 * / delete against verbatim anchors) instead of re-emitting the whole report —
 * untouched text survives by construction, and the model's output stays small
 * however long the report grows (no token-ceiling cuts). After applying, each
 * pass is still gated deterministically: the seven canonical headings must
 * survive ({@link #looksLikeReport}), at least one of the new packet's markers
 * must appear (arrival), and the text must not net-shrink. A failed pass
 * retries ONCE; a packet whose both attempts fail is requeued for a second
 * integration round instead of being dropped. Each pass also carries the
 * MATERIAL PLAN in its letterhead (which packets are done / current / pending —
 * the model must know what it is working on), and the per-pass input is
 * budgeted against the model's context window
 * ({@link AgentBrain#contextTokens()}), splitting a packet that would not fit
 * instead of letting Ollama truncate silently.
 *
 * <p>One generation at a time on a single daemon worker; progress is posted as
 * {@link DeepDiveProgressEvent}s so the UI can narrate the passes.
 */
@Singleton
public class DeepDiveService {

    private static final Logger LOG = LoggerFactory.getLogger(DeepDiveService.class);

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** Newest news that ride the material — the DD must be CURRENT (watchlist rule). */
    private static final long NEWS_MAX_AGE_DAYS = 30;
    private static final int MAX_NEWS = 12;
    /**
     * Articles read + distilled INLINE during collect (one small model call per
     * article, body capped by the reader) — bounds the DD's extra runtime while
     * the top of the relevance-ranked pool still gets full substance.
     */
    private static final int MAX_DIGESTED_ARTICLES = 8;
    /** Per news PACKET ceiling: with digests an article block is fat — small packets keep every integrate pass light (the author's one-article-at-a-time read). */
    private static final int NEWS_PACKET_CHARS = 2800;
    /** Per-article digest cap inside a packet (mirrors the wire brief's cap). */
    private static final int MAX_DIGEST_CHARS = 500;
    private static final int MAX_INSIDER_DEALS = 8;
    private static final int MAX_SHORT_POSITIONS = 8;
    private static final int MAX_KEY_FIGURE_YEARS = 6;
    private static final int MAX_BALANCE_YEARS = 4;
    private static final int MAX_EVENTS = 4;
    private static final int MAX_WIRE_LINES = 6;
    /**
     * Per room PACKET ceiling — the room rides as SEVERAL small packets like the
     * news (its own integrate pass each), so "Der Raum" can be a real retelling
     * of the discussion instead of a snippet-thin lean read.
     */
    private static final int ROOM_PACKET_CHARS = 2400;
    /** How many room packets at most (newest evidence kept when over). */
    private static final int MAX_ROOM_PACKETS = 4;
    /** The TradingCentral comment prose is attributed context, not the star — capped. */
    private static final int MAX_TECHNICAL_COMMENT_CHARS = 400;
    /**
     * Runaway backstop on the archived report — NOT a working budget (raised from
     * 8000 on 2026-07-13: the old cap sat below what seven grown sections
     * legitimately reach and guillotined the tail, i.e. exactly "Der Raum").
     * Growth is bounded upstream by the model's numPredict; hitting this logs.
     */
    private static final int MAX_REPORT_CHARS = 12000;
    /**
     * Mirrors the deep-dive model's numPredict in {@link OllamaModelFactory} —
     * the output half of the context-window budget every pass must respect.
     */
    private static final int DD_NUM_PREDICT = 3584;
    /** Conservative chars-per-token estimate for German prose (gemma tokenizer). */
    private static final double CHARS_PER_TOKEN = 3.0;
    /** Below this per-pass packet budget we warn instead of splitting further. */
    private static final int MIN_SPLIT_CHARS = 1200;
    /** Cap on the QA pass's condensed fact sheet. */
    private static final int FACT_SHEET_CHARS = 3500;
    /**
     * Anti-compression gate: a revised report shorter than this fraction of its
     * predecessor (minus a small correction slack) is a compressed pass, not an
     * edit — the standing report stays.
     */
    private static final double MIN_LENGTH_RATIO = 0.9;
    private static final int LENGTH_SLACK_CHARS = 200;

    private final SubjectRegistry subjectRegistry;
    private final AgentBrain brain;
    private final ChatGateway chatGateway;
    private final DeepDiveArchive archive;
    private final ApplicationEventBus eventBus;

    // -- optional data legs (production-wired via Guice, absent in tests) --
    private volatile EditorialAgent editorialAgent;
    private volatile de.bsommerfeld.wsbg.terminal.core.price.PriceSource priceSource;
    private volatile de.bsommerfeld.wsbg.terminal.core.price.VenueStatsSource venueStatsSource;
    private volatile de.bsommerfeld.wsbg.terminal.core.price.InstrumentFactsSource factsSource;
    private volatile de.bsommerfeld.wsbg.terminal.core.price.AnalystViewSource analystSource;
    private volatile de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDiveSource deepDiveSource;
    private volatile de.bsommerfeld.wsbg.terminal.core.price.ShortInterestSource shortInterestSource;
    private volatile de.bsommerfeld.wsbg.terminal.core.price.InsiderDealingsSource insiderSource;
    private volatile de.bsommerfeld.wsbg.terminal.aggregator.NewsAggregator newsAggregator;

    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "deepdive");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public DeepDiveService(SubjectRegistry subjectRegistry, AgentBrain brain, LlmGate llmGate,
            DeepDiveArchive archive, ApplicationEventBus eventBus) {
        this.subjectRegistry = subjectRegistry;
        this.brain = brain;
        this.chatGateway = new ChatGateway(brain, llmGate);
        this.archive = archive;
        this.eventBus = eventBus;
    }

    @com.google.inject.Inject(optional = true)
    void setEditorialAgent(EditorialAgent editorialAgent) {
        this.editorialAgent = editorialAgent;
    }

    @com.google.inject.Inject(optional = true)
    void setPriceSource(de.bsommerfeld.wsbg.terminal.core.price.PriceSource source) {
        this.priceSource = source;
    }

    @com.google.inject.Inject(optional = true)
    void setVenueStatsSource(de.bsommerfeld.wsbg.terminal.core.price.VenueStatsSource source) {
        this.venueStatsSource = source;
    }

    @com.google.inject.Inject(optional = true)
    void setInstrumentFactsSource(de.bsommerfeld.wsbg.terminal.core.price.InstrumentFactsSource source) {
        this.factsSource = source;
    }

    @com.google.inject.Inject(optional = true)
    void setAnalystViewSource(de.bsommerfeld.wsbg.terminal.core.price.AnalystViewSource source) {
        this.analystSource = source;
    }

    @com.google.inject.Inject(optional = true)
    void setCompanyDeepDiveSource(de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDiveSource source) {
        this.deepDiveSource = source;
    }

    @com.google.inject.Inject(optional = true)
    void setShortInterestSource(de.bsommerfeld.wsbg.terminal.core.price.ShortInterestSource source) {
        this.shortInterestSource = source;
    }

    @com.google.inject.Inject(optional = true)
    void setInsiderDealingsSource(de.bsommerfeld.wsbg.terminal.core.price.InsiderDealingsSource source) {
        this.insiderSource = source;
    }

    @com.google.inject.Inject(optional = true)
    void setNewsAggregator(de.bsommerfeld.wsbg.terminal.aggregator.NewsAggregator aggregator) {
        this.newsAggregator = aggregator;
    }

    // -- public API (bridge) --

    public boolean isBusy() {
        return busy.get();
    }

    public List<DeepDiveRecord> recent(int limit) {
        return archive.recent(limit);
    }

    public Optional<DeepDiveRecord> byId(String id) {
        return archive.byId(id);
    }

    /** Deletes one archived report — an explicit user action from the widget. */
    public boolean delete(String id) {
        return archive.delete(id);
    }

    /**
     * Kicks one report generation. The input is a TICKER, never a free name
     * (user mandate 2026-07-13 "ausschließlich Ticker akzeptieren") — the whole
     * collect keys on it: local lists first, the resolver as dynamic fallback,
     * and the canonical name is LOOKED UP from the ticker for the name-addressed
     * legs (Google News, WSO). Returns {@code false} (and does nothing) when a
     * generation is already running or the input is not ticker-shaped — one DD
     * at a time, the passes are heavyweight model calls.
     */
    public boolean generate(String rawTicker) {
        String ticker = rawTicker == null ? "" : rawTicker.strip().toUpperCase(Locale.ROOT);
        if (!looksLikeTicker(ticker)) {
            LOG.info("[DEEPDIVE] rejected non-ticker input: '{}'", rawTicker);
            return false;
        }
        if (!busy.compareAndSet(false, true)) return false;
        worker.execute(() -> {
            try {
                run(ticker);
            } catch (Exception e) {
                LOG.warn("[DEEPDIVE] generation for '{}' failed: {}", ticker, e.getMessage());
                finish(ticker, false, null);
            } finally {
                busy.set(false); // safety net — finish() already dropped it
            }
        });
        return true;
    }

    /**
     * Ticker shape: no whitespace, ≤15 chars, only symbol alphabet (letters,
     * digits, and the venue/index/futures/FX markers {@code . - ^ =}). Covers
     * RHM.DE, 005930.KS, BRK-B, ^GDAXI, CC=F, BTC-USD — and rejects free names.
     */
    static boolean looksLikeTicker(String s) {
        if (s == null || s.isEmpty() || s.length() > 15) return false;
        boolean alnum = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                alnum = true;
                continue;
            }
            if (c != '.' && c != '-' && c != '^' && c != '=') return false;
        }
        return alnum;
    }

    /**
     * Ends a generation: {@code busy} falls BEFORE the finished event goes out,
     * so the UI's final state push (the bridge reads {@link #isBusy()} on it)
     * already sees idle — otherwise the progress display would linger until the
     * next unrelated push (user report 2026-07-13).
     */
    private void finish(String subject, boolean success, String reportId) {
        busy.set(false);
        eventBus.post(new DeepDiveFinishedEvent(subject, success, reportId));
    }

    // -- the generation pipeline --

    private void run(String subject) {
        long t0 = System.currentTimeMillis();
        eventBus.post(new DeepDiveStartedEvent(subject));
        eventBus.post(new DeepDiveProgressEvent(subject, "collect"));

        Material m = collect(subject);
        String lang = brain.getUserLanguage().code();
        boolean de = "de".equalsIgnoreCase(lang);
        // The material is fed SEQUENTIALLY, one thematic packet per pass, along
        // the report's fixed section skeleton (the DD's red thread) — nothing is
        // capped away, and no single pass ever approaches num_ctx (user mandate
        // 2026-07-13: "nacheinander reinreichen, statt cappen").
        List<Packet> packets = buildPackets(subject, m, de);
        LOG.info("[DEEPDIVE] '{}' material collected: {} packet(s), {} chars total "
                        + "(isin={}, ticker={}, news={}, evidence={})",
                subject, packets.size(),
                packets.stream().mapToInt(p -> p.text().length()).sum(),
                m.isin, m.ticker, m.news.size(), m.evidenceCount);
        if (packets.isEmpty()) {
            finish(subject, false, null);
            return;
        }

        ChatModel model = brain.getDeepDiveModel() != null
                ? brain.getDeepDiveModel() : brain.getProseModel();
        if (model == null) {
            finish(subject, false, null);
            return;
        }
        String langName = brain.getUserLanguage().displayName();
        String header = header(subject, m);
        // Exact heading literals to enforce — only for the two languages whose
        // prompts pin the names; a third language translates them freely and
        // falls back to the count-based check.
        List<String> headings = de ? SECTIONS_DE
                : "en".equalsIgnoreCase(lang) ? SECTIONS_EN : null;
        Set<Integer> validNums = new HashSet<>(sourceNumbers(m).values());

        // Pass 1 — the draft: the full seven-section skeleton from the FIRST
        // packet (identity/profile); later packets are worked in, never
        // invented. One retry — an aborted DD over a single hiccup is waste.
        eventBus.post(new DeepDiveProgressEvent(subject, "draft"));
        String draftPrompt = PromptLoader.loadLocalized("deepdive-draft", lang)
                .replace("{{LANGUAGE}}", langName);
        String report = null;
        for (int attempt = 1; attempt <= 2 && report == null; attempt++) {
            String draft = cleanReport(chatGateway.chat(model, draftPrompt,
                    header + materialPlan(packets, 0, Set.of())
                            + packetBlock(packets.get(0))));
            if (looksLikeReport(draft, headings)) report = draft;
            else LOG.warn("[DEEPDIVE] '{}' draft attempt {} whiffed ({} chars){}",
                    subject, attempt, draft == null ? 0 : draft.length(),
                    attempt == 1 ? " — retrying" : " — aborting");
        }
        if (report == null) {
            finish(subject, false, null);
            return;
        }

        // Passes 2..n — integration: standing report + ONE new packet per pass.
        // The model emits EDIT OPERATIONS (EditScript), never the full report —
        // untouched text survives mechanically, and the output stays small no
        // matter how long the report grows (no token-ceiling cuts). Structure,
        // packet arrival and no-shrink are still gated after applying; a failed
        // pass retries once, a twice-failed packet goes into a SECOND
        // integration round instead of being dropped.
        String integratePrompt = PromptLoader.loadLocalized("deepdive-integrate", lang)
                .replace("{{LANGUAGE}}", langName);
        record Job(Packet packet, int originIdx) {}
        List<Job> pending = new ArrayList<>();
        for (int i = 1; i < packets.size(); i++) pending.add(new Job(packets.get(i), i));
        Set<Integer> doneIdx = new HashSet<>(Set.of(0));
        int fed = 0;
        for (int round = 1; round <= 2 && !pending.isEmpty(); round++) {
            List<Job> lostThisRound = new ArrayList<>();
            java.util.ArrayDeque<Job> queue = new java.util.ArrayDeque<>(pending);
            while (!queue.isEmpty()) {
                Job job = queue.pollFirst();
                Packet p = job.packet();
                // Context budget: prompt + letterhead + standing report + packet
                // + numPredict must fit num_ctx — Ollama truncates silently past
                // it, which reads as the model suddenly getting dumb. A packet
                // that no longer fits is split at line boundaries instead.
                int budget = inputBudgetChars() - integratePrompt.length()
                        - header.length() - report.length() - 400;
                if (p.text().length() > budget) {
                    List<Packet> parts = splitPacket(p, Math.max(budget, MIN_SPLIT_CHARS));
                    if (parts.size() > 1) {
                        LOG.info("[DEEPDIVE] '{}' packet '{}' over the context budget "
                                        + "({} > {} chars) — split into {} parts.",
                                subject, p.displayName(), p.text().length(),
                                budget, parts.size());
                        for (int j = parts.size() - 1; j >= 0; j--) {
                            queue.addFirst(new Job(parts.get(j), job.originIdx()));
                        }
                        continue;
                    }
                    if (budget < MIN_SPLIT_CHARS) {
                        LOG.warn("[DEEPDIVE] '{}' context budget down to {} chars with "
                                        + "the report at {} chars — pass may be truncated.",
                                subject, budget, report.length());
                    }
                }
                fed++;
                eventBus.post(new DeepDiveProgressEvent(subject, "integrate",
                        fed + "/" + (fed + queue.size()) + " · " + p.displayName()));
                String plan = materialPlan(packets, job.originIdx(), doneIdx);
                String accepted = null;
                for (int attempt = 1; attempt <= 2 && accepted == null; attempt++) {
                    String opsRaw = cleanReport(chatGateway.chat(model, integratePrompt,
                            header + plan + "STANDING REPORT:\n" + report
                                    + "\n\n" + packetBlock(p)));
                    EditScript script = EditScript.parse(opsRaw);
                    if (script.isEmpty()) {
                        // New material ALWAYS warrants at least one op — a noop
                        // or formatless reply is a whiffed pass, not a decision.
                        LOG.warn("[DEEPDIVE] '{}' integrate '{}' attempt {} emitted no "
                                        + "usable operations ({} chars).",
                                subject, p.displayName(), attempt,
                                opsRaw == null ? 0 : opsRaw.length());
                        continue;
                    }
                    EditScript.Result res = script.apply(report);
                    script.logFailures(subject, "integrate '" + p.displayName() + "'", res);
                    boolean structure = looksLikeReport(res.text(), headings);
                    boolean arrived = packetArrived(p, res.text(), validNums);
                    boolean grown = notShrunk(report, res.text());
                    if (res.applied() > 0 && structure && arrived && grown) {
                        // Untouched text survived mechanically; a marker can only
                        // vanish through an explicit REPLACE/DELETE — that is a
                        // correction, not silent loss. Log it for the audit trail.
                        Set<Integer> lostMarkers = markersIn(report);
                        lostMarkers.removeAll(markersIn(res.text()));
                        if (!lostMarkers.isEmpty()) {
                            LOG.info("[DEEPDIVE] '{}' integrate '{}' removed marker(s) {} "
                                            + "via explicit edit — corrected claim.",
                                    subject, p.displayName(), lostMarkers);
                        }
                        // Deterministic repetition scrub after every accepted
                        // pass — INSERT drift must never accumulate.
                        accepted = dedupeRepeats(res.text());
                        if (accepted.length() < res.text().length()) {
                            LOG.info("[DEEPDIVE] '{}' integrate '{}': dedupe removed {} "
                                            + "repeated chars.", subject, p.displayName(),
                                    res.text().length() - accepted.length());
                        }
                    } else {
                        LOG.warn("[DEEPDIVE] '{}' integrate '{}' attempt {} rejected "
                                        + "(ops applied {}/{}, structure={}, arrival={}, grown={}).",
                                subject, p.displayName(), attempt,
                                res.applied(), script.size(), structure, arrived, grown);
                    }
                }
                if (accepted != null) {
                    report = accepted;
                    doneIdx.add(job.originIdx());
                } else {
                    lostThisRound.add(job);
                }
            }
            pending = lostThisRound;
            if (!pending.isEmpty() && round == 1) {
                LOG.warn("[DEEPDIVE] '{}' {} packet(s) failed round 1 — requeued "
                        + "for a second integration round.", subject, pending.size());
            }
        }
        if (!pending.isEmpty()) {
            LOG.warn("[DEEPDIVE] '{}' {} packet(s) never made it into the report: {}",
                    subject, pending.size(),
                    pending.stream().map(j -> j.packet().displayName()).toList());
        }
        // Belt-and-braces: a delivered source no line ever cites is suspicious —
        // its packet either whiffed twice or the model paraphrased markerless.
        Set<Integer> uncited = new HashSet<>(validNums);
        uncited.removeAll(markersIn(report));
        if (!uncited.isEmpty()) {
            LOG.warn("[DEEPDIVE] '{}' delivered source(s) never cited in the text: {}",
                    subject, uncited);
        }

        // Adversarial Q&A: a skeptical reviewer interrogates the assembled
        // report AGAINST the condensed fact sheet — the one pass that can catch
        // a figure an integrate pass distorted (the report alone cannot).
        eventBus.post(new DeepDiveProgressEvent(subject, "qa"));
        String qaPrompt = PromptLoader.loadLocalized("deepdive-qa", lang)
                .replace("{{LANGUAGE}}", langName);
        String sheet = factSheet(m);
        int sheetBudget = inputBudgetChars() - qaPrompt.length() - header.length()
                - report.length() - 400;
        if (sheet.length() > Math.max(sheetBudget, 0)) {
            LOG.warn("[DEEPDIVE] '{}' fact sheet trimmed to the context budget "
                    + "({} -> {} chars).", subject, sheet.length(), Math.max(sheetBudget, 0));
            sheet = sheet.substring(0, Math.max(sheetBudget, 0));
        }
        String qa = chatGateway.chat(model, qaPrompt,
                header + "REPORT UNDER REVIEW:\n" + report
                        + "\n\nFACT SHEET (the verified material, condensed):\n" + sheet);
        boolean qaUsable = looksLikeQa(qa);
        if (!qaUsable && qa != null && !qa.isBlank()) {
            LOG.warn("[DEEPDIVE] '{}' QA pass returned no usable F:/A: pairs — skipping the final pass.",
                    subject);
        }

        // Final: work the review in and smooth the red thread — as EDIT
        // OPERATIONS against the standing report (compression is mechanically
        // impossible; a review that confirmed everything says <<<NOOP). One
        // retry; a whiffed final degrades gracefully to the integrated report,
        // which is complete on its own.
        if (qaUsable) {
            eventBus.post(new DeepDiveProgressEvent(subject, "final"));
            String finalPrompt = PromptLoader.loadLocalized("deepdive-final", lang)
                    .replace("{{LANGUAGE}}", langName);
            for (int attempt = 1; attempt <= 2; attempt++) {
                String opsRaw = cleanReport(chatGateway.chat(model, finalPrompt,
                        header + "REPORT:\n" + report
                                + "\n\nADVERSARIAL REVIEW (Q&A):\n" + qa));
                EditScript script = EditScript.parse(opsRaw);
                if (script.isNoop()) {
                    LOG.info("[DEEPDIVE] '{}' final pass: review confirmed the report — no edits.",
                            subject);
                    break;
                }
                if (script.isEmpty()) {
                    LOG.warn("[DEEPDIVE] '{}' final attempt {} emitted no usable operations{}",
                            subject, attempt,
                            attempt == 1 ? " — retrying" : " — keeping the integrated report");
                    continue;
                }
                EditScript.Result res = script.apply(report);
                script.logFailures(subject, "final", res);
                boolean structure = looksLikeReport(res.text(), headings);
                boolean grown = notShrunk(report, res.text());
                if (res.applied() > 0 && structure && grown) {
                    report = dedupeRepeats(res.text());
                    break;
                }
                LOG.warn("[DEEPDIVE] '{}' final attempt {} rejected (ops applied {}/{}, "
                                + "structure={}, grown={}){}", subject, attempt,
                        res.applied(), script.size(), structure, grown,
                        attempt == 1 ? " — retrying" : " — keeping the integrated report");
            }
        }

        report = dedupeRepeats(scrubPlaceholders(report));
        // Source discipline: the model may only COPY the material's [n] markers —
        // any number it invented (no matching source) is deterministically removed.
        report = scrubUnknownSourceMarkers(report, new HashSet<>(sourceNumbers(m).values()));
        if (report.length() > MAX_REPORT_CHARS) {
            LOG.warn("[DEEPDIVE] '{}' report hit the runaway backstop ({} > {} chars) "
                    + "— truncating.", subject, report.length(), MAX_REPORT_CHARS);
            report = report.substring(0, MAX_REPORT_CHARS - 1).stripTrailing() + "…";
        }
        // The source register: OURS, deterministic, appended AFTER every model
        // pass — the model never writes, edits or renumbers it (user mandate
        // 2026-07-13, Wikipedia-style footnotes).
        String sources = sourcesSection(m, "de".equalsIgnoreCase(lang));
        if (!sources.isEmpty()) {
            report = report.stripTrailing() + "\n\n" + sources;
        }
        // Figures: deterministic SVG from the verified material (the model never
        // draws), frozen into the record so UI and PDF show the same picture.
        List<DeepDiveRecord.ChartFigure> charts = new DeepDiveCharts(lang)
                .build(m.snapshot, m.deepDive, m.analystView, m.shortInterest, m.insiderDealings);
        DeepDiveRecord record = new DeepDiveRecord(
                "dd-" + UUID.randomUUID().toString().substring(0, 8),
                subject, m.canonicalName, m.ticker, m.isin,
                Instant.now().getEpochSecond(), report,
                m.snapshot != null && m.snapshot.hasPrice() ? m.snapshot.price() : null,
                m.snapshot != null && m.snapshot.hasPrice() ? m.snapshot.currency() : null,
                m.evidenceCount, m.news.size(),
                System.currentTimeMillis() - t0,
                charts);
        archive.append(record);
        LOG.info("[DEEPDIVE] '{}' report {} archived: {} chars, {} figure(s), {} ms "
                        + "(draft + {} integration pass(es) + qa + final).",
                subject, record.id(), report.length(), charts.size(),
                record.durationMs(), fed);
        finish(subject, true, record.id());
    }

    /** Everything the collect step gathered — nulls/empties where a leg had nothing. */
    /** Package-private for the material-completeness test. */
    static final class Material {
        String canonicalName;
        String ticker;
        String isin;
        MarketSnapshot snapshot;
        de.bsommerfeld.wsbg.terminal.core.price.VenueStats venueStats;
        de.bsommerfeld.wsbg.terminal.core.price.InstrumentFacts facts;
        de.bsommerfeld.wsbg.terminal.core.price.FundFacts fundFacts;
        AnalystView analystView;
        CompanyDeepDive deepDive;
        ShortInterest shortInterest;
        InsiderDealings insiderDealings;
        List<RawNewsItem> news = List.of();
        /** link -> key-fact digest of the FULL article, read during collect. */
        Map<String, String> digests = Map.of();
        SubjectUnit unit;
        /**
         * EVERY registry unit that belongs to this subject — the room speaks
         * name AND ticker (a unit that says "Outlook" without ever writing
         * OTLK belongs to the OTLK DD too); the room packets draw from their
         * union. First entry = best-scoring (the primary {@link #unit}).
         */
        List<SubjectUnit> roomUnits = List.of();
        int evidenceCount;
    }

    /**
     * Gathers every leg, each individually guarded — a failing source costs its
     * block, never the report. Identity is TICKER-FIRST (user mandate
     * 2026-07-13): the ticker is checked against the LOCAL lists (the feed-wide
     * {@link SubjectRegistry}) before any network, the pipeline's fully-wired
     * resolver is the dynamic fallback AND the name lookup (the name-addressed
     * news legs need it), and the ISIN is salvaged from the L&S snapshot's
     * symbol when no leg carried it — without an ISIN the five German data legs
     * silently stay dark (live-observed with OTLK 2026-07-13: L&S knew the ISIN,
     * the material never saw it).
     */
    private Material collect(String ticker) {
        Material m = new Material();
        m.ticker = ticker;
        m.canonicalName = ticker;

        // LOCAL lists first: the feed-wide registry already knows most tickers —
        // identity, ISIN, last snapshot and the room's evidence, zero network.
        m.unit = findUnit(ticker, ticker);
        if (m.unit != null) {
            m.evidenceCount = m.unit.evidenceCount();
            if (m.unit.canonicalName() != null && !m.unit.canonicalName().isBlank()) {
                m.canonicalName = m.unit.canonicalName();
            }
            if (m.unit.isin() != null && !m.unit.isin().isBlank()) m.isin = m.unit.isin();
            if (m.unit.snapshot() != null) m.snapshot = m.unit.snapshot();
            LOG.info("[DEEPDIVE] '{}' identity from local registry: '{}' (isin={})",
                    ticker, m.canonicalName, m.isin);
        }

        // Dynamic fallback + refresh via the pipeline's resolver: brings the
        // canonical NAME (Google News/WSO query on it), a fresh snapshot and the
        // Yahoo news leg — also for tickers the registry already knew.
        EditorialAgent agent = editorialAgent;
        if (agent != null) {
            try {
                TickerResolver.ResolvedSubject rs = agent.tickerResolver().resolve(ticker, 0);
                if (rs != null && !rs.unresolved()) {
                    if (rs.canonicalName() != null && !rs.canonicalName().isBlank()) {
                        m.canonicalName = rs.canonicalName();
                    }
                    if (rs.ticker() != null && !rs.ticker().isBlank()) m.ticker = rs.ticker();
                    if (rs.isin() != null && !rs.isin().isBlank()) m.isin = rs.isin();
                    if (rs.snapshot() != null) m.snapshot = rs.snapshot();
                    if (rs.news() != null && !rs.news().isEmpty()) m.news = rs.news();
                }
            } catch (Exception e) {
                LOG.debug("[DEEPDIVE] resolve failed for '{}': {}", ticker, e.getMessage());
            }
        }

        // Room binding on NAME *and* TICKER (user mandate 2026-07-13): now that
        // the resolver delivered the canonical name, gather EVERY unit that
        // belongs to this subject — a "name:outlook" unit holding the cage's
        // chatter must not lose to an evidence-less exact-ticker unit, and the
        // room packets draw from the union of all of them.
        m.roomUnits = findRoomUnits(m.canonicalName, m.ticker);
        if (!m.roomUnits.isEmpty()) {
            m.unit = m.roomUnits.get(0);
            m.evidenceCount = 0;
            for (SubjectUnit u : m.roomUnits) m.evidenceCount += u.evidenceCount();
            if (m.isin == null) m.isin = m.unit.isin();
            if (m.snapshot == null) m.snapshot = m.unit.snapshot();
            if (m.roomUnits.size() > 1) {
                LOG.info("[DEEPDIVE] '{}' room union: {} unit(s) ({} mentions total)",
                        ticker, m.roomUnits.size(), m.evidenceCount);
            }
        }

        // Fresh price when the resolver pass had none.
        if (m.snapshot == null && priceSource != null && m.ticker != null) {
            try {
                priceSource.snapshot(new de.bsommerfeld.wsbg.terminal.core.price.PriceRef(
                        m.canonicalName, m.ticker, m.isin)).ifPresent(s -> m.snapshot = s);
            } catch (Exception e) {
                LOG.debug("[DEEPDIVE] price failed: {}", e.getMessage());
            }
        }

        // ISIN salvage: an L&S snapshot carries the ISIN as its symbol. Without
        // this, a resolver path that never pinned the ISIN (ticker fast-path)
        // skips Tradegate, onvista, Consorsbank, Bundesanzeiger AND BaFin.
        if ((m.isin == null || m.isin.isBlank()) && m.snapshot != null
                && WeatherStatsCollector.looksLikeIsin(m.snapshot.symbol())) {
            m.isin = m.snapshot.symbol();
            LOG.info("[DEEPDIVE] '{}' ISIN salvaged from the price snapshot: {}",
                    m.ticker, m.isin);
        }

        String isin = m.isin;
        if (isin != null && !isin.isBlank()) {
            try {
                if (venueStatsSource != null) {
                    m.venueStats = venueStatsSource.statsByIsin(isin).orElse(null);
                }
            } catch (Exception e) {
                LOG.debug("[DEEPDIVE] venue stats failed: {}", e.getMessage());
            }
            try {
                if (factsSource != null) {
                    m.facts = factsSource.factsByIsin(isin).orElse(null);
                    if (m.facts == null) m.fundFacts = factsSource.fundFactsByIsin(isin).orElse(null);
                }
            } catch (Exception e) {
                LOG.debug("[DEEPDIVE] facts failed: {}", e.getMessage());
            }
            try {
                // refresh(): a fixed report must carry TODAY's street view, not a session cache.
                if (analystSource != null) m.analystView = analystSource.refresh(isin).orElse(null);
            } catch (Exception e) {
                LOG.debug("[DEEPDIVE] analyst view failed: {}", e.getMessage());
            }
            try {
                if (deepDiveSource != null) m.deepDive = deepDiveSource.deepDiveByIsin(isin).orElse(null);
            } catch (Exception e) {
                LOG.debug("[DEEPDIVE] company deep dive failed: {}", e.getMessage());
            }
            try {
                if (shortInterestSource != null) {
                    m.shortInterest = shortInterestSource.byIsin(isin).orElse(null);
                }
            } catch (Exception e) {
                LOG.debug("[DEEPDIVE] short interest failed: {}", e.getMessage());
            }
            try {
                if (insiderSource != null) m.insiderDealings = insiderSource.byIsin(isin).orElse(null);
            } catch (Exception e) {
                LOG.debug("[DEEPDIVE] insider dealings failed: {}", e.getMessage());
            }
        }

        // Triangulated news (Yahoo + WSO + Google News), freshness-cut.
        try {
            if (newsAggregator != null) {
                List<RawNewsItem> pooled = newsAggregator.newsFor(m.ticker, m.canonicalName, MAX_NEWS);
                if (!pooled.isEmpty()) m.news = pooled;
            }
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] news failed: {}", e.getMessage());
        }
        Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(NEWS_MAX_AGE_DAYS));
        m.news = m.news.stream()
                .filter(n -> n.publishedAt() == null || !n.publishedAt().isBefore(cutoff))
                .limit(MAX_NEWS)
                .toList();

        // Read the articles BEHIND the headlines, one at a time: each is fetched,
        // capped (~6k chars) and distilled in its own small model call — a long
        // article can never overload the model — and later rides its own small
        // news packet into the integrate loop (the author's re-read, user mandate
        // 2026-07-13). Cache shared with the wire's background digest lane.
        EditorialAgent digestAgent = editorialAgent;
        if (digestAgent != null && !m.news.isEmpty()) {
            Map<String, String> digests = new LinkedHashMap<>();
            int total = Math.min(m.news.size(), MAX_DIGESTED_ARTICLES);
            int done = 0;
            for (RawNewsItem item : m.news) {
                if (done >= MAX_DIGESTED_ARTICLES) break;
                if (item.link() == null || item.link().isBlank()) continue;
                done++;
                eventBus.post(new DeepDiveProgressEvent(ticker, "collect",
                        "Artikel " + done + "/" + total));
                try {
                    String digest = digestAgent.newsDigester().digestNow(item.link());
                    if (!digest.isBlank()) digests.put(item.link().trim(), digest);
                } catch (Exception e) {
                    LOG.debug("[DEEPDIVE] article digest failed for {}: {}",
                            item.link(), e.getMessage());
                }
            }
            m.digests = digests;
        }
        return m;
    }

    /** Deterministic unit lookup: exact ticker/name, then significant-word subset (watchlist rule). */
    private SubjectUnit findUnit(String name, String ticker) {
        List<SubjectUnit> hits = findRoomUnits(name, ticker);
        return hits.isEmpty() ? null : hits.get(0);
    }

    /** Sanity cap — the room union never needs more than a handful of units. */
    private static final int MAX_ROOM_UNITS = 5;

    /**
     * EVERY registry unit belonging to the subject, best first: exact
     * ticker/name matches, then significant-word-subset name matches (the
     * watchlist rule) — so the "Outlook" name unit rides along with the OTLK
     * ticker unit and the room's chatter is found under both identities.
     */
    private List<SubjectUnit> findRoomUnits(String name, String ticker) {
        record Scored(SubjectUnit unit, int score) {
        }
        Set<String> nameWords = NameMatcher.significantWords(name);
        List<Scored> hits = new ArrayList<>();
        for (SubjectUnit u : subjectRegistry.all()) {
            int score = -1;
            if (name.equalsIgnoreCase(u.ticker()) || name.equalsIgnoreCase(u.canonicalName())
                    || (ticker != null && ticker.equalsIgnoreCase(u.ticker()))) {
                score = 1_000_000;
            } else if (!nameWords.isEmpty()) {
                Set<String> unitWords = NameMatcher.significantWords(u.canonicalName());
                boolean subset = !unitWords.isEmpty()
                        && (unitWords.containsAll(nameWords) || nameWords.containsAll(unitWords));
                if (subset) score = 0;
            }
            if (score < 0) continue;
            score += (u.isInstrument() ? 100_000 : 0) + Math.min(u.evidenceCount(), 10_000);
            hits.add(new Scored(u, score));
        }
        hits.sort((a, b) -> Integer.compare(b.score(), a.score()));
        List<SubjectUnit> out = new ArrayList<>(Math.min(hits.size(), MAX_ROOM_UNITS));
        for (Scored s : hits) {
            if (out.size() >= MAX_ROOM_UNITS) break;
            out.add(s.unit());
        }
        return out;
    }

    // -- the material packets (the DD's red thread) --

    /**
     * One thematic material packet, fed to the model in its OWN pass.
     * {@code briefLabel} heads the packet in the model's brief (English, like
     * every brief label); {@code sectionsHint} names the target sections in the
     * REPORT's language — the routing hint must literally match the headings
     * the model edits (a 4B must not translate to route); {@code displayName}
     * narrates the pass in the UI (user language).
     */
    record Packet(String briefLabel, String displayName, String sectionsHint, String text) {
    }

    /**
     * The report's canonical section skeleton (the DD's red thread), shaped
     * like a professional research note: description, thesis, situation,
     * financial trend, valuation vs peers, catalysts/risks, and the house's
     * own room section. The literals are pinned in the prompts AND enforced
     * by {@link #looksLikeReport} — heading drift is a rejected pass.
     */
    static final List<String> SECTIONS_DE = List.of(
            "Worum es geht", "These", "Lage", "Fundamentale Entwicklung",
            "Bewertung und Wettbewerb", "Katalysatoren und Risiken", "Der Raum");
    static final List<String> SECTIONS_EN = List.of(
            "What it is about", "Thesis", "Situation", "Fundamental development",
            "Valuation and competition", "Catalysts and risks", "The room");

    /** Defensive per-packet ceiling — packets are naturally far below this. */
    private static final int MAX_PACKET_CHARS = 7000;

    /** The compact identity header every pass carries (date, subject, ticker, ISIN, index). */
    static String header(String subject, Material m) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("NOW: ").append(STAMP.format(Instant.now())).append('\n');
        sb.append("SUBJECT: ").append(m.canonicalName);
        if (m.ticker != null) sb.append(" (Ticker ").append(m.ticker).append(')');
        if (m.isin != null) sb.append(" [ISIN ").append(m.isin).append(']');
        if (m.deepDive != null && m.deepDive.indexName() != null) {
            sb.append(", member of ").append(m.deepDive.indexName());
        }
        return sb.append('\n').append('\n').toString();
    }

    /**
     * Splits the collected material into thematic packets ordered along the
     * report skeleton — profile first (the draft's ground), the room LAST (the
     * divergence read needs the fact layers in place). Empty packets are
     * dropped; the identity packet always exists (it carries the market line
     * or, at minimum, the profile stub) so the draft pass always has ground.
     * {@code de} picks the language of the section hints — they must literally
     * match the report's headings.
     */
    static List<Packet> buildPackets(String subject, Material m, boolean de) {
        List<String> h = de ? SECTIONS_DE : SECTIONS_EN;
        List<Packet> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder(2048);
        // Deterministic source numbers ([n], Wikipedia-style): assigned HERE from
        // what actually delivered, stamped onto every material block header so the
        // model can carry the marker beside a fact — the source REGISTER itself is
        // appended deterministically after the final pass, never model-written.
        Map<String, Integer> nums = sourceNumbers(m);

        appendMarket(sb, m.snapshot, nums);
        appendProfile(sb, m, nums);
        appendBoard(sb, m.deepDive, nums);
        addPacket(out, "Company and market", "Profil & Markt",
                h.get(0) + ", " + h.get(1), sb);
        // The draft pass grounds on packet 0 = identity. When every identity leg
        // missed (unresolved subject), an honest stub takes that slot so the
        // skeleton still gets laid down before the remaining packets arrive.
        if (out.isEmpty()) {
            out.add(new Packet("Company and market", "Profil & Markt",
                    h.get(0),
                    "(no verified company/market material — the subject could not be resolved)\n"));
        }

        appendFundamentals(sb, m.deepDive, nums);
        appendBalance(sb, m.deepDive, nums);
        appendPeers(sb, m.deepDive, nums);
        addPacket(out, "Fundamentals", "Fundamentaldaten",
                h.get(3) + ", " + h.get(4) + ", " + h.get(1), sb);

        appendAnalysts(sb, m.analystView, nums);
        appendInsider(sb, m.insiderDealings, nums);
        appendShorts(sb, m.shortInterest, nums);
        addPacket(out, "Street and insiders", "Analysten & Insider",
                h.get(4) + ", " + h.get(5) + ", " + h.get(1), sb);

        appendTechnical(sb, m.deepDive, nums);
        appendPerformance(sb, m.deepDive, nums);
        appendTrading(sb, m.venueStats, m.facts, nums);
        addPacket(out, "Chart and trading", "Charttechnik & Handel",
                h.get(2) + ", " + h.get(5), sb);

        // News ride in as SEVERAL small packets — each its own integrate pass, so
        // the model works the articles (with their full-text digests) in one
        // handful at a time and no single pass carries the whole pool.
        List<String> newsBlocks = new ArrayList<>();
        for (int i = 0; i < m.news.size(); i++) {
            newsBlocks.add(newsItemBlock(m.news.get(i), m.digests, mark(nums, "news:" + i)));
        }
        if (!newsBlocks.isEmpty()) {
            List<StringBuilder> chunks = new ArrayList<>();
            StringBuilder cur = null;
            for (String block : newsBlocks) {
                if (cur == null || cur.length() + block.length() > NEWS_PACKET_CHARS) {
                    cur = new StringBuilder(NEWS_PACKET_CHARS + 256);
                    chunks.add(cur);
                }
                cur.append(block);
            }
            for (int c = 0; c < chunks.size(); c++) {
                String part = chunks.size() == 1 ? "" : " (" + (c + 1) + "/" + chunks.size() + ")";
                sb.append("NEWS (verified, last ").append(NEWS_MAX_AGE_DAYS)
                        .append(" days)").append(part).append(":\n").append(chunks.get(c));
                addPacket(out, "News" + part, "News" + part,
                        h.get(2) + ", " + h.get(5) + ", " + h.get(1), sb);
            }
        }

        // The room rides as SEVERAL small packets like the news (its section is
        // a real RETELLING of the discussion now, and a retelling can never be
        // better than its excerpt) — chronological, so the narrative builds and
        // the newest evidence lands last (newest wins on contradiction). Drawn
        // from the UNION of every matching unit (name AND ticker identity).
        List<SubjectUnit> roomUnits = !m.roomUnits.isEmpty() ? m.roomUnits
                : m.unit != null ? List.of(m.unit) : List.of();
        List<String> roomChunks = roomBlocks(roomUnits, nums);
        for (int c = 0; c < roomChunks.size(); c++) {
            String part = roomChunks.size() == 1 ? "" : " (" + (c + 1) + "/" + roomChunks.size() + ")";
            sb.append(roomChunks.get(c));
            addPacket(out, "The room" + part, "Der Käfig" + part, h.get(6), sb);
        }
        return out;
    }

    /**
     * Deterministic source numbering — the fixed leg order, numbers assigned only
     * to legs that actually delivered, then one number PER news article, the room
     * last. Pure function of the material: packet markers and the appended
     * register can never disagree.
     */
    static Map<String, Integer> sourceNumbers(Material m) {
        Map<String, Integer> nums = new LinkedHashMap<>();
        int n = 0;
        if (m.snapshot != null && m.snapshot.hasPrice()) nums.put("price", ++n);
        if (m.venueStats != null) nums.put("venue", ++n);
        if (m.facts != null || m.fundFacts != null) nums.put("profile", ++n);
        if (m.deepDive != null || m.analystView != null) nums.put("consors", ++n);
        if (m.deepDive != null && m.deepDive.technicalView() != null) nums.put("tc", ++n);
        if (m.shortInterest != null) nums.put("shorts", ++n);
        if (m.insiderDealings != null) nums.put("insider", ++n);
        for (int i = 0; i < m.news.size(); i++) nums.put("news:" + i, ++n);
        if (m.unit != null) nums.put("room", ++n);
        return nums;
    }

    private static String mark(Map<String, Integer> nums, String key) {
        Integer n = nums.get(key);
        return n == null ? "" : " [" + n + "]";
    }

    /** One news item as a packet block: marker, date, title, publisher, and the article's key-fact digest underneath. */
    private static String newsItemBlock(RawNewsItem item, Map<String, String> digests, String mark) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("  -").append(mark).append(' ');
        if (item.publishedAt() != null) sb.append('[').append(STAMP.format(item.publishedAt())).append("] ");
        sb.append(item.title());
        if (item.publisher() != null && !item.publisher().isEmpty()) {
            sb.append(" · ").append(item.publisher());
        }
        sb.append('\n');
        String digest = item.link() == null ? null : digests.get(item.link().trim());
        if (digest != null && !digest.isBlank()) {
            String dg = digest.replace('\n', ' ').strip();
            if (dg.length() > MAX_DIGEST_CHARS) dg = dg.substring(0, MAX_DIGEST_CHARS) + "…";
            sb.append("      ").append(dg).append('\n');
        }
        return sb.toString();
    }

    private static void addPacket(List<Packet> out, String briefLabel, String displayName,
            String sectionsHint, StringBuilder sb) {
        String text = sb.toString();
        sb.setLength(0);
        if (text.isBlank()) return;
        if (text.length() > MAX_PACKET_CHARS) {
            text = text.substring(0, MAX_PACKET_CHARS) + "\n  (packet trimmed)\n";
        }
        out.add(new Packet(briefLabel, displayName, sectionsHint, text));
    }

    /** The packet as the model sees it: labeled, with its target-section hint. */
    private static String packetBlock(Packet p) {
        return "NEW MATERIAL — " + p.briefLabel()
                + " (belongs in the sections: " + p.sectionsHint() + "):\n" + p.text();
    }

    /**
     * The FULL material as one text — the packets joined. Only the
     * completeness test reads this (it asserts every leg lands SOMEWHERE);
     * the model itself always receives packets one pass at a time.
     */
    static String buildMaterial(String subject, Material m) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append(header(subject, m));
        for (Packet p : buildPackets(subject, m, false)) sb.append(p.text());
        return sb.toString();
    }

    private static void appendMarket(StringBuilder sb, MarketSnapshot s, Map<String, Integer> nums) {
        if (s == null || !s.hasPrice()) return;
        sb.append("MARKET (verified)").append(mark(nums, "price")).append(": ").append(fmt2(s.price()));
        if ("PTS".equals(s.currency())) sb.append(" points (index)");
        else if (s.currency() != null && !s.currency().isEmpty()) sb.append(' ').append(s.currency());
        if (Double.isFinite(s.dayChangePercent())) {
            sb.append(String.format(Locale.ROOT, ", day %+.2f%%", s.dayChangePercent()));
        }
        if (Double.isFinite(s.dayLow()) && Double.isFinite(s.dayHigh())) {
            sb.append(", day range ").append(fmt2(s.dayLow())).append('-').append(fmt2(s.dayHigh()));
        }
        sb.append('\n');
    }

    private static void appendProfile(StringBuilder sb, Material m, Map<String, Integer> nums) {
        CompanyDeepDive.Profile p = m.deepDive != null ? m.deepDive.profile() : null;
        var facts = m.facts;
        if (p == null && facts == null && m.fundFacts == null) return;
        // The profile line mixes two feeds: Consorsbank profile + onvista facts.
        sb.append("PROFILE (verified)")
                .append(p != null ? mark(nums, "consors") : "")
                .append(facts != null || m.fundFacts != null ? mark(nums, "profile") : "")
                .append(": ");
        if (p != null) {
            if (p.website() != null) sb.append("official website ").append(p.website()).append("; ");
            if (p.hqCity() != null || p.hqCountry() != null) {
                sb.append("HQ ").append(String.join(", ",
                        java.util.stream.Stream.of(p.hqCity(), p.hqCountry())
                                .filter(java.util.Objects::nonNull).toList())).append("; ");
            }
            if (Double.isFinite(p.marketCapEur())) {
                sb.append(String.format(Locale.ROOT, "market cap %.1fB EUR; ", p.marketCapEur() / 1e9));
            }
            if (p.sharesOutstanding() > 0) {
                // Space grouping — comma-grouped counts misread in German prose.
                sb.append(String.format(Locale.ROOT, "%,d shares; ", p.sharesOutstanding())
                        .replace(',', ' '));
            }
        }
        if (facts != null) {
            if (facts.sector() != null || facts.branch() != null) {
                sb.append("sector ").append(String.join(" / ",
                        java.util.stream.Stream.of(facts.sector(), facts.branch())
                                .filter(java.util.Objects::nonNull).toList())).append("; ");
            }
            if (facts.employees() >= 0) {
                sb.append(facts.employees()).append(" employees");
                if (facts.employeesLabel() != null) sb.append(" (").append(facts.employeesLabel()).append(')');
                sb.append("; ");
            }
            // Valuation fallback: when the Consorsbank deep dive missed (its
            // key-figure years would carry these), the onvista headline figures
            // must not silently vanish from the model's view.
            if (m.deepDive == null) {
                if (Double.isFinite(facts.marketCapEur()) && (p == null || !Double.isFinite(p.marketCapEur()))) {
                    sb.append(String.format(Locale.ROOT, "market cap %.1fB EUR; ",
                            facts.marketCapEur() / 1e9));
                }
                if (Double.isFinite(facts.peRatio())) {
                    sb.append(String.format(Locale.ROOT, "P/E %.1f (%s); ",
                            facts.peRatio(), facts.peLabel()));
                }
                if (Double.isFinite(facts.divYieldPercent())) {
                    sb.append(String.format(Locale.ROOT, "dividend yield %.2f%% (%s); ",
                            facts.divYieldPercent(), facts.divLabel()));
                }
            }
        }
        if (m.fundFacts != null) {
            var f = m.fundFacts;
            sb.append("FUND").append(mark(nums, "profile")).append(": ").append(f.name());
            if (Double.isFinite(f.terPercent())) {
                sb.append(String.format(Locale.ROOT, ", TER %.2f%%", f.terPercent()));
            }
            if (Double.isFinite(f.volumeEur())) {
                sb.append(String.format(Locale.ROOT, ", volume %.1fB EUR", f.volumeEur() / 1e9));
            }
            if (f.benchmark() != null) sb.append(", benchmark ").append(f.benchmark());
            if (f.morningstarRating() > 0) sb.append(", Morningstar ").append(f.morningstarRating()).append("/5");
            if (!f.topHoldings().isEmpty()) {
                sb.append("; top holdings: ");
                for (int i = 0; i < Math.min(5, f.topHoldings().size()); i++) {
                    var h = f.topHoldings().get(i);
                    if (i > 0) sb.append(", ");
                    sb.append(h.name());
                    if (Double.isFinite(h.weightPercent())) {
                        sb.append(String.format(Locale.ROOT, " %.1f%%", h.weightPercent()));
                    }
                }
            }
            sb.append("; ");
        }
        sb.append('\n');
        if (p != null && p.portrait() != null) {
            sb.append("COMPANY PORTRAIT (verified)").append(mark(nums, "consors"))
                    .append(": ").append(p.portrait()).append('\n');
        }
    }

    private static void appendFundamentals(StringBuilder sb, CompanyDeepDive d, Map<String, Integer> nums) {
        if (d == null || d.keyFigures().isEmpty()) return;
        sb.append("KEY FIGURES BY FISCAL YEAR (verified, 'e' = consensus estimate)")
                .append(mark(nums, "consors")).append(":\n");
        List<CompanyDeepDive.KeyFigureYear> years = d.keyFigures();
        int from = Math.max(0, years.size() - MAX_KEY_FIGURE_YEARS);
        for (CompanyDeepDive.KeyFigureYear y : years.subList(from, years.size())) {
            sb.append("  ").append(y.label()).append(':');
            appendFig(sb, " EPS ", y.eps());
            appendFig(sb, ", dividend ", y.dividendPerShare());
            appendFig(sb, " (yield ", y.dividendYieldPercent());
            if (Double.isFinite(y.dividendYieldPercent())) sb.append("%)");
            appendFig(sb, ", P/E ", y.peRatio());
            appendFig(sb, ", PEG ", y.pegRatio());
            appendFig(sb, ", EBIT margin ", y.ebitMarginPercent());
            if (Double.isFinite(y.ebitMarginPercent())) sb.append('%');
            appendFig(sb, ", equity ratio ", y.equityRatioPercent());
            if (Double.isFinite(y.equityRatioPercent())) sb.append('%');
            if (y.employees() >= 0) sb.append(", ").append(y.employees()).append(" employees");
            sb.append('\n');
        }
    }

    private static void appendBalance(StringBuilder sb, CompanyDeepDive d, Map<String, Integer> nums) {
        if (d == null || d.balanceSheet().isEmpty()) return;
        sb.append("BALANCE SHEET (verified, thousands EUR)").append(mark(nums, "consors")).append(":\n");
        List<CompanyDeepDive.BalanceSheetYear> years = d.balanceSheet();
        int from = Math.max(0, years.size() - MAX_BALANCE_YEARS);
        for (CompanyDeepDive.BalanceSheetYear y : years.subList(from, years.size())) {
            sb.append("  ").append(y.label()).append(':');
            appendFig(sb, " turnover ", y.turnover());
            appendFig(sb, ", net income ", y.netIncome());
            appendFig(sb, ", equity ", y.equityCapital());
            appendFig(sb, ", cashflow ", y.cashflowNet());
            appendFig(sb, ", R&D ", y.researchExpenses());
            sb.append('\n');
        }
    }

    private static void appendBoard(StringBuilder sb, CompanyDeepDive d, Map<String, Integer> nums) {
        if (d == null || d.board().isEmpty()) return;
        sb.append("BOARDS (verified)").append(mark(nums, "consors")).append(": ");
        int n = 0;
        for (CompanyDeepDive.BoardMember b : d.board()) {
            if (++n > 8) break;
            if (n > 1) sb.append("; ");
            sb.append(b.name()).append(" (").append(b.board());
            if ("CHAIRMAN".equalsIgnoreCase(b.role())) sb.append(", Vorsitz");
            sb.append(')');
        }
        sb.append('\n');
    }

    private static void appendAnalysts(StringBuilder sb, AnalystView av, Map<String, Integer> nums) {
        if (av == null) return;
        if (av.hasRatings()) {
            sb.append("ANALYSTS (verified)").append(mark(nums, "consors")).append(": ")
                    .append(av.total()).append(" covering — ")
                    .append(av.buy()).append(" buy / ").append(av.overweight()).append(" overweight / ")
                    .append(av.hold()).append(" hold / ").append(av.underweight()).append(" underweight / ")
                    .append(av.sell()).append(" sell");
            if (av.buy3m() >= 0) {
                sb.append(String.format(Locale.ROOT, " (3 months ago: %d/%d/%d/%d/%d)",
                        av.buy3m(), av.overweight3m(), av.hold3m(), av.underweight3m(), av.sell3m()));
            }
            if (Double.isFinite(av.targetPrice())) {
                sb.append(String.format(Locale.ROOT, "; consensus target %.2f %s",
                        av.targetPrice(), av.targetCurrency() == null ? "" : av.targetCurrency()));
                if (Double.isFinite(av.expectedUpsidePercent())) {
                    sb.append(String.format(Locale.ROOT, " (%+.1f%% vs current)",
                            av.expectedUpsidePercent()));
                }
            }
            if (av.upgrades() >= 0 && av.downgrades() >= 0) {
                sb.append("; recent revisions ").append(av.upgrades()).append(" up / ")
                        .append(av.downgrades()).append(" down");
            }
            sb.append('\n');
        }
        long nowEpoch = Instant.now().getEpochSecond();
        int n = 0;
        StringBuilder ev = new StringBuilder();
        for (var event : av.events()) {
            if (event.atEpochSeconds() < nowEpoch) continue;
            if (++n > MAX_EVENTS) break;
            if (ev.length() > 0) ev.append("; ");
            ev.append(STAMP.format(Instant.ofEpochSecond(event.atEpochSeconds())), 0, 10);
            if (event.title() != null) ev.append(' ').append(event.title());
        }
        if (ev.length() > 0) {
            sb.append("UPCOMING EVENTS (verified)").append(mark(nums, "consors"))
                    .append(": ").append(ev).append('\n');
        }
    }

    private static void appendInsider(StringBuilder sb, InsiderDealings id, Map<String, Integer> nums) {
        if (id == null) return;
        if (id.deals().isEmpty()) {
            sb.append("INSIDER DEALINGS (verified, BaFin)").append(mark(nums, "insider"))
                    .append(": none reported\n");
            return;
        }
        sb.append("INSIDER DEALINGS (verified, BaFin, newest first)")
                .append(mark(nums, "insider")).append(":\n");
        int n = 0;
        for (InsiderDealings.InsiderDeal deal : id.deals()) {
            if (++n > MAX_INSIDER_DEALS) break;
            sb.append("  - ");
            if (deal.dealDateIso() != null) sb.append('[').append(deal.dealDateIso()).append("] ");
            sb.append(deal.person());
            if (deal.positionStatus() != null) sb.append(" (").append(deal.positionStatus()).append(')');
            sb.append(": ").append(deal.dealType());
            if (Double.isFinite(deal.volumeEur())) {
                sb.append(String.format(Locale.ROOT, " %.0f EUR", deal.volumeEur()));
            }
            if (Double.isFinite(deal.avgPrice())) {
                sb.append(String.format(Locale.ROOT, " @ %.2f %s", deal.avgPrice(),
                        deal.currency() == null ? "" : deal.currency()));
            }
            sb.append('\n');
        }
    }

    private static void appendShorts(StringBuilder sb, ShortInterest si, Map<String, Integer> nums) {
        if (si == null) return;
        if (si.positions().isEmpty()) {
            sb.append("SHORT POSITIONS (verified, Bundesanzeiger register)")
                    .append(mark(nums, "shorts")).append(": no disclosed position >= 0.5%\n");
            return;
        }
        sb.append(String.format(Locale.ROOT,
                "SHORT POSITIONS (verified, Bundesanzeiger register, total disclosed %.2f%%)"
                        + mark(nums, "shorts") + ":\n",
                si.totalDisclosedPercent()));
        int n = 0;
        for (ShortInterest.ShortPosition p : si.positions()) {
            if (++n > MAX_SHORT_POSITIONS) break;
            sb.append(String.format(Locale.ROOT, "  - %s: %.2f%% (%s)%n",
                    p.holder(), p.percent(), p.dateIso()));
        }
    }

    private static void appendTechnical(StringBuilder sb, CompanyDeepDive d, Map<String, Integer> nums) {
        CompanyDeepDive.TechnicalView t = d != null ? d.technicalView() : null;
        if (t == null) return;
        sb.append("CHART-TECHNICAL READ (TradingCentral, attributed third-party view");
        if (t.asOfIso() != null) sb.append(", as of ").append(t.asOfIso());
        sb.append(')').append(mark(nums, "tc")).append(": ");
        appendFig(sb, "pivot ", t.pivot());
        appendFig(sb, "; supports ", t.support1());
        appendFig(sb, " / ", t.support2());
        appendFig(sb, " / ", t.support3());
        appendFig(sb, "; resistances ", t.resistance1());
        appendFig(sb, " / ", t.resistance2());
        appendFig(sb, " / ", t.resistance3());
        if (t.shortTermOpinion() != null) sb.append("; short-term opinion ").append(t.shortTermOpinion());
        if (t.mediumTermOpinion() != null) sb.append("; medium-term opinion ").append(t.mediumTermOpinion());
        sb.append('\n');
        if (t.commentText() != null) {
            String comment = t.commentText().length() <= MAX_TECHNICAL_COMMENT_CHARS
                    ? t.commentText()
                    : t.commentText().substring(0, MAX_TECHNICAL_COMMENT_CHARS - 1)
                            .stripTrailing() + "…";
            sb.append("  Its comment: ").append(comment).append('\n');
        }
    }

    private static void appendPeers(StringBuilder sb, CompanyDeepDive d, Map<String, Integer> nums) {
        if (d == null || d.peers().isEmpty()) return;
        sb.append("PEERS (verified)").append(mark(nums, "consors")).append(": ");
        int n = 0;
        for (CompanyDeepDive.Peer p : d.peers()) {
            if (++n > 5) break;
            if (n > 1) sb.append("; ");
            sb.append(p.name());
            if (Double.isFinite(p.marketCapEur())) {
                sb.append(String.format(Locale.ROOT, " (mcap %.1fB EUR", p.marketCapEur() / 1e9));
                if (Double.isFinite(p.peRatio())) {
                    sb.append(String.format(Locale.ROOT, ", P/E %.1f", p.peRatio()));
                }
                sb.append(')');
            }
        }
        sb.append('\n');
    }

    private static void appendPerformance(StringBuilder sb, CompanyDeepDive d, Map<String, Integer> nums) {
        CompanyDeepDive.PerformanceStats p = d != null ? d.performance() : null;
        if (p == null) return;
        sb.append("PERFORMANCE (verified)").append(mark(nums, "consors")).append(':');
        appendPct(sb, " 1w ", p.perf1w());
        appendPct(sb, ", 4w ", p.perf4w());
        appendPct(sb, ", 3m ", p.perf3m());
        appendPct(sb, ", 6m ", p.perf6m());
        appendPct(sb, ", 52w ", p.perf52w());
        appendFig(sb, "; volatility 30d ", p.vola30d());
        appendFig(sb, ", 250d ", p.vola250d());
        if (Double.isFinite(p.high52w())) {
            sb.append(String.format(Locale.ROOT, "; 52w high %.2f (%s), low %.2f (%s)",
                    p.high52w(), p.high52wDateIso(), p.low52w(), p.low52wDateIso()));
        }
        sb.append('\n');
    }

    private static void appendTrading(StringBuilder sb,
            de.bsommerfeld.wsbg.terminal.core.price.VenueStats vs,
            de.bsommerfeld.wsbg.terminal.core.price.InstrumentFacts facts,
            Map<String, Integer> nums) {
        if (vs == null) return;
        sb.append("TRADING (Tradegate, verified)").append(mark(nums, "venue")).append(": ");
        if (vs.volumeShares() >= 0) {
            sb.append(vs.volumeShares()).append(" shares");
            if (facts != null && Double.isFinite(facts.avgVolume30d())) {
                sb.append(String.format(Locale.ROOT, " (30d average %.0f)", facts.avgVolume30d()));
            }
        }
        if (vs.turnoverEur() >= 0) {
            sb.append(String.format(Locale.ROOT, " / %.1fM EUR turnover", vs.turnoverEur() / 1_000_000.0));
        }
        if (vs.executions() >= 0) sb.append(", ").append(vs.executions()).append(" executions");
        if (vs.hasQuote()) {
            sb.append(String.format(Locale.ROOT, ", bid/ask %.2f/%.2f", vs.bid(), vs.ask()));
            if (Double.isFinite(vs.spreadPercent())) {
                sb.append(String.format(Locale.ROOT, " (spread %.2f%%)", vs.spreadPercent()));
            }
        }
        sb.append('\n');
    }

    /**
     * The room's material as one or more chunk texts (each becomes its own
     * packet, ≤ {@link #ROOM_PACKET_CHARS}): the first carries the price anchor,
     * the last carries the already-published wire lines, evidence runs
     * CHRONOLOGICALLY through all of them so the retelling can follow the
     * narrative. Draws from the UNION of every matching unit (the room speaks
     * name AND ticker — "Outlook" chatter belongs to the OTLK DD), deduplicated
     * by mention identity. Newest evidence is kept when the total budget
     * ({@code ROOM_PACKET_CHARS * MAX_ROOM_PACKETS}) is exceeded.
     */
    static List<String> roomBlocks(List<SubjectUnit> units, Map<String, Integer> nums) {
        String head = "ROOM EVIDENCE (r/wallstreetbetsGER, unverified)" + mark(nums, "room");
        if (units == null || units.isEmpty()) {
            return List.of(head + ":\n  (the room has not taken this subject up)\n");
        }
        SubjectUnit primary = units.get(0);
        List<StringBuilder> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder(ROOM_PACKET_CHARS + 256);
        cur.append(head).append(":\n");
        chunks.add(cur);
        // The primary unit's price anchor: since-first-mention is the room's own scoreboard.
        if (primary.firstPrice() != null && primary.firstPrice() > 0 && primary.firstPriceAt() != null) {
            cur.append("  First mentioned ").append(STAMP.format(primary.firstPriceAt()), 0, 10)
                    .append(" at ").append(fmt2(primary.firstPrice()));
            MarketSnapshot s = primary.snapshot();
            if (s != null && s.hasPrice()) {
                cur.append(String.format(Locale.ROOT, " (%+.1f%% since)",
                        (s.price() - primary.firstPrice()) / primary.firstPrice() * 100.0));
            }
            cur.append('\n');
        }
        // Union of every unit's mentions, deduped by mention identity — the same
        // comment attributed to both the ticker and the name unit rides once.
        Map<String, SubjectUnit.EvidenceRef> byKey = new LinkedHashMap<>();
        for (SubjectUnit u : units) {
            for (SubjectUnit.EvidenceRef ref : u.evidence()) {
                byKey.putIfAbsent(ref.threadId() + "|" + ref.commentId() + "|" + ref.snippet(), ref);
            }
        }
        List<SubjectUnit.EvidenceRef> evidence = new ArrayList<>(byKey.values());
        evidence.sort(java.util.Comparator.comparingLong(SubjectUnit.EvidenceRef::addedAtEpoch));
        if (evidence.isEmpty()) cur.append("  (none in the current window)\n");
        int start = evidence.size();
        int budget = ROOM_PACKET_CHARS * MAX_ROOM_PACKETS;
        while (start > 0 && budget - evidence.get(start - 1).snippet().length() - 24 >= 0) {
            start--;
            budget -= evidence.get(start).snippet().length() + 24;
        }
        if (start > 0) cur.append("  (").append(start).append(" older mentions omitted)\n");
        for (SubjectUnit.EvidenceRef ref : evidence.subList(start, evidence.size())) {
            String line = "  - [" + STAMP.format(Instant.ofEpochSecond(ref.addedAtEpoch()))
                    + "] " + ref.snippet() + "\n";
            if (cur.length() + line.length() > ROOM_PACKET_CHARS) {
                cur = new StringBuilder(ROOM_PACKET_CHARS + 256);
                cur.append(head).append(" (continued):\n");
                chunks.add(cur);
            }
            cur.append(line);
        }
        // Wire lines: union over all units, chronological, newest MAX_WIRE_LINES.
        List<SubjectUnit.UnitHeadline> headlines = new ArrayList<>();
        for (SubjectUnit u : units) headlines.addAll(u.headlines());
        headlines.sort(java.util.Comparator.comparingLong(SubjectUnit.UnitHeadline::atEpoch));
        if (!headlines.isEmpty()) {
            cur.append("WIRE LINES ALREADY PUBLISHED FOR THIS SUBJECT:\n");
            int from = Math.max(0, headlines.size() - MAX_WIRE_LINES);
            for (SubjectUnit.UnitHeadline h : headlines.subList(from, headlines.size())) {
                cur.append("  - [").append(STAMP.format(Instant.ofEpochSecond(h.atEpoch())))
                        .append("] ").append(h.text()).append('\n');
            }
        }
        List<String> out = new ArrayList<>(chunks.size());
        for (StringBuilder c : chunks) out.add(c.toString());
        return out;
    }

    // -- helpers --

    /** Strips code fences a model occasionally wraps its markdown in. */
    private static String cleanReport(String raw) {
        if (raw == null) return "";
        String s = raw.strip();
        if (s.startsWith("```")) {
            int firstBreak = s.indexOf('\n');
            if (firstBreak > 0) s = s.substring(firstBreak + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
            s = s.strip();
        }
        return s;
    }

    /** The report's fixed section count (the DD's red thread). */
    private static final int SECTION_COUNT = 7;

    /**
     * A usable report has real length and ALL seven sections — a pass that ran
     * into the token ceiling loses its tail sections (live-observed 2026-07-13:
     * the final pass was cut mid-sentence and "Der Raum" vanished), and such an
     * output must never replace the standing report. With a known heading list
     * (de/en) each canonical heading must appear as a LINE-LEADING "## " line,
     * in order — a pass that renames or reorders sections is a rejected pass.
     * A third language translates its headings freely and gets the count-based
     * fallback.
     */
    static boolean looksLikeReport(String s, List<String> headings) {
        if (s == null || s.length() < 500) return false;
        if (headings == null) {
            int sections = 0;
            for (int i = s.indexOf("## "); i >= 0; i = s.indexOf("## ", i + 1)) {
                if (i == 0 || s.charAt(i - 1) == '\n') sections++;
            }
            return sections >= SECTION_COUNT;
        }
        int from = 0;
        for (String heading : headings) {
            from = indexOfHeading(s, heading, from);
            if (from < 0) return false;
        }
        return true;
    }

    /**
     * Finds a line-leading {@code "## <heading>"} whose rest-of-line is only
     * whitespace or a stray colon (a 4B occasionally appends one). Returns the
     * index AFTER the match, or -1.
     */
    private static int indexOfHeading(String s, String heading, int from) {
        String needle = "## " + heading;
        for (int i = s.indexOf(needle, from); i >= 0; i = s.indexOf(needle, i + 1)) {
            if (i > 0 && s.charAt(i - 1) != '\n') continue;
            int j = i + needle.length();
            boolean clean = true;
            while (j < s.length() && s.charAt(j) != '\n') {
                char c = s.charAt(j++);
                if (c != ' ' && c != '\t' && c != ':' && c != '\r') {
                    clean = false;
                    break;
                }
            }
            if (clean) return j;
        }
        return -1;
    }

    // -- the deterministic pass gates (marker retention/arrival, no-shrink) --

    private static final java.util.regex.Pattern MARKER_NUM =
            java.util.regex.Pattern.compile("\\[(\\d{1,2})\\]");

    /** Every {@code [n]} source marker a text carries. */
    static Set<Integer> markersIn(String text) {
        Set<Integer> out = new HashSet<>();
        if (text == null) return out;
        java.util.regex.Matcher mt = MARKER_NUM.matcher(text);
        while (mt.find()) out.add(Integer.parseInt(mt.group(1)));
        return out;
    }

    /**
     * Retention gate: every source marker of the prior report must survive the
     * revision — a vanished marker means a cited statement was dropped or
     * paraphrased away, which the prompts forbid but only a check enforces.
     */
    static boolean retainsMarkers(String prior, String revised) {
        return markersIn(revised).containsAll(markersIn(prior));
    }

    /**
     * Arrival gate: a packet that carries source markers must leave at least one
     * of them in the revised report — otherwise its material never landed and
     * the pass silently dropped the packet. A markerless packet (identity stub)
     * has nothing to verify.
     */
    static boolean packetArrived(Packet p, String revised, Set<Integer> validNums) {
        Set<Integer> packetMarks = markersIn(p.text());
        packetMarks.retainAll(validNums);
        if (packetMarks.isEmpty()) return true;
        Set<Integer> revisedMarks = markersIn(revised);
        for (Integer n : packetMarks) {
            if (revisedMarks.contains(n)) return true;
        }
        return false;
    }

    /**
     * Anti-compression gate: an EDIT with new material never legitimately
     * shrinks the report beyond a small correction slack — a shorter revision
     * is a 4B compressing under length pressure, i.e. information loss.
     */
    static boolean notShrunk(String prior, String revised) {
        if (revised == null) return false;
        return revised.length() >= (int) (prior.length() * MIN_LENGTH_RATIO) - LENGTH_SLACK_CHARS;
    }

    /** A usable QA pass carries at least three F:/A: pairs of its contract. */
    static boolean looksLikeQa(String qa) {
        if (qa == null || qa.isBlank()) return false;
        int f = 0;
        int a = 0;
        for (String line : qa.split("\n")) {
            String stripped = line.strip();
            if (stripped.startsWith("F:")) f++;
            else if (stripped.startsWith("A:")) a++;
        }
        return f >= 3 && a >= 3;
    }

    // -- context budget, material plan, packet splitting, fact sheet --

    /**
     * How many input chars a pass may spend: the model's context window minus
     * its own output reservation, char-estimated conservatively. DD passes must
     * budget against this — Ollama TRUNCATES a longer prompt silently.
     */
    private int inputBudgetChars() {
        return (int) ((brain.contextTokens() - DD_NUM_PREDICT) * CHARS_PER_TOKEN);
    }

    /**
     * The MATERIAL PLAN line every draft/integrate pass carries in its
     * letterhead: all packets in feeding order, each marked done / THIS PASS /
     * pending. The model must know what it is working on — "leg missing" and
     * "leg still coming" are different worlds for the prose (user mandate
     * 2026-07-13: "die KI MUSS wissen, woran sie arbeitet").
     */
    static String materialPlan(List<Packet> packets, int currentIdx, Set<Integer> doneIdx) {
        StringBuilder sb = new StringBuilder(200);
        sb.append("MATERIAL PLAN (one packet per pass): ");
        for (int i = 0; i < packets.size(); i++) {
            if (i > 0) sb.append(" · ");
            sb.append(i + 1).append(". ").append(packets.get(i).briefLabel());
            sb.append(i == currentIdx ? " [THIS PASS]"
                    : doneIdx.contains(i) ? " [done]" : " [pending]");
        }
        return sb.append('\n').append('\n').toString();
    }

    /**
     * Splits an over-budget packet at line boundaries into parts of at most
     * {@code maxChars} (material blocks are line-oriented, so a line is the
     * safe cut). Single-part results return the packet untouched.
     */
    static List<Packet> splitPacket(Packet p, int maxChars) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder(Math.min(p.text().length(), maxChars) + 64);
        for (String line : p.text().split("\n", -1)) {
            if (cur.length() > 0 && cur.length() + line.length() + 1 > maxChars) {
                parts.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(line).append('\n');
        }
        if (cur.length() > 0 && !cur.toString().isBlank()) parts.add(cur.toString());
        if (parts.size() <= 1) return List.of(p);
        List<Packet> out = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            String suffix = " (" + (i + 1) + "/" + parts.size() + ")";
            out.add(new Packet(p.briefLabel() + suffix, p.displayName() + suffix,
                    p.sectionsHint(), parts.get(i)));
        }
        return out;
    }

    /**
     * The QA pass's condensed FACT SHEET: every verifiable figure block of the
     * material, WITHOUT the prose legs (portrait, TradingCentral comment, room
     * snippets, article digests — attributed prose is not figure-checkable).
     * This is what lets the adversarial pass catch a number an integrate pass
     * distorted; the report alone cannot betray that.
     */
    static String factSheet(Material m) {
        Map<String, Integer> nums = sourceNumbers(m);
        StringBuilder sb = new StringBuilder(3072);
        appendMarket(sb, m.snapshot, nums);
        appendProfile(sb, m, nums);
        appendBoard(sb, m.deepDive, nums);
        appendFundamentals(sb, m.deepDive, nums);
        appendBalance(sb, m.deepDive, nums);
        appendPeers(sb, m.deepDive, nums);
        appendAnalysts(sb, m.analystView, nums);
        appendInsider(sb, m.insiderDealings, nums);
        appendShorts(sb, m.shortInterest, nums);
        appendTechnical(sb, m.deepDive, nums);
        appendPerformance(sb, m.deepDive, nums);
        appendTrading(sb, m.venueStats, m.facts, nums);
        for (int i = 0; i < m.news.size(); i++) {
            sb.append(newsItemBlock(m.news.get(i), Map.of(), mark(nums, "news:" + i)));
        }
        StringBuilder out = new StringBuilder(sb.length());
        for (String line : sb.toString().split("\n", -1)) {
            String stripped = line.strip();
            if (stripped.isEmpty()) continue;
            if (stripped.startsWith("COMPANY PORTRAIT") || stripped.startsWith("Its comment:")) {
                continue;
            }
            out.append(line).append('\n');
        }
        String sheet = out.toString();
        if (sheet.length() > FACT_SHEET_CHARS) {
            sheet = sheet.substring(0, FACT_SHEET_CHARS) + "\n  (sheet trimmed)\n";
        }
        return sheet;
    }

    /**
     * Removes every {@code [n]} marker whose number no source carries — a 4B
     * model must never mint its own footnotes. Valid markers pass untouched.
     */
    static String scrubUnknownSourceMarkers(String report, java.util.Set<Integer> valid) {
        if (report == null || report.isEmpty()) return report;
        java.util.regex.Matcher mt =
                java.util.regex.Pattern.compile(" ?\\[(\\d{1,2})\\]").matcher(report);
        StringBuilder out = new StringBuilder(report.length());
        while (mt.find()) {
            boolean known = valid.contains(Integer.parseInt(mt.group(1)));
            mt.appendReplacement(out,
                    known ? java.util.regex.Matcher.quoteReplacement(mt.group()) : "");
        }
        mt.appendTail(out);
        return out.toString();
    }

    /**
     * The rendered source register ("## Quellen"), matching {@link #sourceNumbers}
     * one to one. Deterministic house text — never model output.
     */
    static String sourcesSection(Material m, boolean de) {
        Map<String, Integer> nums = sourceNumbers(m);
        if (nums.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(512);
        sb.append("## ").append(de ? "Quellen" : "Sources").append('\n');
        for (Map.Entry<String, Integer> e : nums.entrySet()) {
            sb.append("- [").append(e.getValue()).append("] ")
                    .append(sourceLabel(m, e.getKey(), de)).append('\n');
        }
        return sb.toString();
    }

    private static String sourceLabel(Material m, String key, boolean de) {
        switch (key) {
            case "price": {
                String venue = m.snapshot != null && m.snapshot.exchangeName() != null
                        && !m.snapshot.exchangeName().isBlank()
                        ? m.snapshot.exchangeName() : "Yahoo Finance";
                return venue + (de ? " - Kurs- und Handelsdaten" : " - price and market data");
            }
            case "venue":
                return "Tradegate Exchange" + (de ? " - Orderbuch- und Umsatzdaten"
                        : " - order book and turnover data");
            case "profile":
                return "onvista" + (de ? " - Unternehmensprofil und Bewertungskennzahlen"
                        : " - company profile and valuation figures");
            case "consors":
                return "Consorsbank Financial-Info" + (de
                        ? " - Kennzahlen, Bilanz, Analystenschätzungen, Termine"
                        : " - key figures, balance sheet, analyst estimates, corporate dates");
            case "tc":
                return "TradingCentral (via Consorsbank)" + (de
                        ? " - charttechnische Einschätzung" : " - chart-technical view");
            case "shorts":
                return "Bundesanzeiger" + (de ? " - Leerverkaufsregister (Netto-Shortpositionen)"
                        : " - short-selling register (net short positions)");
            case "insider":
                return "BaFin" + (de ? " - Directors' Dealings (§ 19 MAR)"
                        : " - directors' dealings (art. 19 MAR)");
            case "room":
                return "r/wallstreetbetsGER" + (de ? " - Diskussion im Käfig (unverifiziert)"
                        : " - the room's discussion (unverified)");
            default: {
                int idx = Integer.parseInt(key.substring("news:".length()));
                RawNewsItem item = m.news.get(idx);
                StringBuilder sb = new StringBuilder(96);
                sb.append(item.publisher() == null || item.publisher().isEmpty()
                        ? (de ? "Presse" : "Press") : item.publisher());
                sb.append(" - \u201E").append(item.title()).append('\u201C');
                if (item.publishedAt() != null) {
                    sb.append(" (").append(STAMP.format(item.publishedAt()), 0, 10).append(')');
                }
                return sb.toString();
            }
        }
    }

    /**
     * Removes placeholder lines that survived the passes — the fixed literal the
     * draft plants for not-yet-fed sections. Deterministic belt-and-braces: the
     * prompts demand the replacement, a 4B occasionally leaves one standing.
     */
    /** Below this normalized length, sentence dedupe stays out — short phrases collide legitimately. */
    private static final int MIN_DEDUPE_SENTENCE_CHARS = 80;

    /**
     * Deterministic repetition scrub (live-observed with SAP 2026-07-13): the
     * edit protocol's INSERT drift plants near-identical paragraphs and verbatim
     * sentence repeats — the same sentence stood in the report FOUR times, and
     * the model's own cleanup DELETEs missed their anchors — so the terminal
     * removes exact repeats itself, after every accepted pass and before
     * archiving. Whole duplicate paragraphs go first, then long verbatim
     * sentence repeats across the whole report; the FIRST occurrence wins,
     * heading lines and the section literals (placeholder / honest sentence)
     * are never touched. A repeat with different source markers is a different
     * claim and survives (markers are part of the normalized text).
     */
    static String dedupeRepeats(String report) {
        if (report == null || report.isEmpty()) return report;
        Set<String> seenBlocks = new HashSet<>();
        Set<String> seenSentences = new HashSet<>();
        StringBuilder out = new StringBuilder(report.length());
        for (String rawBlock : report.split("\n\\s*\n")) {
            String block = rawBlock.strip();
            if (block.isEmpty()) continue;
            if (block.startsWith("## ")) {
                int nl = block.indexOf('\n');
                String heading = nl < 0 ? block : block.substring(0, nl).strip();
                appendBlock(out, heading);
                block = nl < 0 ? "" : block.substring(nl + 1).strip();
                if (block.isEmpty()) continue;
            }
            String blockNorm = normalizeForDedupe(block);
            if (!isExemptLiteral(blockNorm) && !seenBlocks.add(blockNorm)) continue;
            StringBuilder kept = new StringBuilder(block.length());
            for (String sentence : splitSentenceish(block)) {
                String norm = normalizeForDedupe(sentence);
                if (norm.length() >= MIN_DEDUPE_SENTENCE_CHARS && !isExemptLiteral(norm)
                        && !seenSentences.add(norm)) {
                    continue;
                }
                if (kept.length() > 0) kept.append(' ');
                kept.append(sentence.strip());
            }
            if (kept.length() > 0) appendBlock(out, kept.toString());
        }
        return out.toString();
    }

    private static void appendBlock(StringBuilder out, String block) {
        if (out.length() > 0) out.append("\n\n");
        out.append(block);
    }

    /**
     * Sentence-ish segments: cut after {@code .!?} ONLY when followed by
     * whitespace (or block end), so a ".)"-terminated literal stays whole and
     * every character lands in exactly one segment. Imprecise on German dates
     * ("am 24. Juli") — harmless: a fragment only ever repeats when its
     * enclosing duplicated text repeats, and kept fragments are rejoined with
     * single spaces.
     */
    private static List<String> splitSentenceish(String block) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < block.length(); i++) {
            char c = block.charAt(i);
            if ((c == '.' || c == '!' || c == '?')
                    && (i + 1 >= block.length() || Character.isWhitespace(block.charAt(i + 1)))) {
                out.add(block.substring(start, i + 1));
                int j = i + 1;
                while (j < block.length() && Character.isWhitespace(block.charAt(j))) j++;
                start = j;
                i = j - 1;
            }
        }
        if (start < block.length()) out.add(block.substring(start));
        return out;
    }

    private static String normalizeForDedupe(String s) {
        return s.strip().replaceAll("\\s+", " ");
    }

    /** The section literals may legitimately stand in several sections at once. */
    private static boolean isExemptLiteral(String normalized) {
        return normalized.equals("(Dieser Abschnitt folgt mit dem nächsten Materialpaket.)")
                || normalized.equals("Hierzu liegen keine verifizierten Daten vor.")
                || normalized.equals("No verified data is available for this section.");
    }

    private static String scrubPlaceholders(String report) {
        StringBuilder out = new StringBuilder(report.length());
        for (String line : report.split("\n", -1)) {
            String stripped = line.strip();
            if (stripped.equals("(Dieser Abschnitt folgt mit dem nächsten Materialpaket.)")) continue;
            out.append(line).append('\n');
        }
        return out.toString().strip();
    }

    private static void appendFig(StringBuilder sb, String label, double v) {
        if (Double.isFinite(v)) sb.append(label).append(fmt2(v));
    }

    private static void appendPct(StringBuilder sb, String label, double v) {
        if (Double.isFinite(v)) sb.append(label).append(String.format(Locale.ROOT, "%+.1f%%", v));
    }

    private static String fmt2(double v) {
        // Grouping SPACES, never commas: "36,800" in the material reads as 36.8
        // in German prose, and the model copies literals verbatim (live-observed
        // "Umsatz auf 36,800 Mio. EUR" — ambiguous). "36 800" is unambiguous.
        return String.format(Locale.ROOT, Math.abs(v) >= 1000 ? "%,.0f" : "%.2f", v)
                .replace(',', ' ');
    }
}
