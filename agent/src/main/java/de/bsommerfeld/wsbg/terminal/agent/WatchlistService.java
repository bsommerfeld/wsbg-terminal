package de.bsommerfeld.wsbg.terminal.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.event.WatchlistChangedEvent;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The AI watchlist — the "status-KI" concept: per user-chosen subject ONE standing
 * dossier (a timeless report + a one-line TLDR) that the model REVISES whenever the
 * subject's feed-wide {@link SubjectUnit} gains evidence, instead of writing wire
 * lines. Aggregates ALL unit content — including evidence already covered by
 * published headlines — because the dossier is the subject's cumulative state, not
 * a delta feed.
 *
 * <p><b>Fundament, not Tageszettel:</b> the report is persisted permanently
 * ({@link WatchlistStore}) and written in a timeless register with absolute date
 * stamps, so tomorrow's evidence (long after today's session snapshot died) still
 * only REVISES the standing text — the dossier never restarts from zero.
 *
 * <p>Mapping: an entry names a subject; it binds to a live unit deterministically
 * (ticker/name equality, then significant-word subset). An entry whose subject the
 * feed hasn't produced yet ("Zukunftsname") simply stays unmapped and is re-tried
 * against new units on every tick — the moment the room first mentions it, the
 * entry latches on and the first report is written.
 *
 * <p>LLM budget: one revision at a time (single tick thread), through the shared
 * {@link LlmGate} like every other model call, with a per-entry settle window and
 * revision cooldown — the watchlist must never starve the wire.
 */
@Singleton
public class WatchlistService {

    private static final Logger LOG = LoggerFactory.getLogger(WatchlistService.class);

    /** Tick cadence — cheap bookkeeping; the LLM only runs when a revision is due. */
    private static final long TICK_MS = 15_000;
    /** Fresh evidence settles this long before a revision, so a hot minute batches into ONE edit. */
    private static final long SETTLE_MS = 90_000;
    /** Minimum gap between two revisions of the same entry. */
    private static final long COOLDOWN_MS = 300_000;

    /** Char budget for the room-evidence block of the revision brief (newest kept). */
    private static final int EVIDENCE_CHAR_BUDGET = 4000;
    private static final int MAX_NEWS_IN_BRIEF = 8;
    private static final int MAX_HEADLINES_IN_BRIEF = 6;
    /** Hard caps on the model's reply — a 4B occasionally rambles; the dossier stays compact. */
    private static final int MAX_REPORT_CHARS = 2400;
    private static final int MAX_TLDR_CHARS = 160;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** One watchlist entry at runtime: the persisted dossier + transient mapping/timing state. */
    static final class Entry {
        final String id;
        final String name;
        final long createdAtEpoch;
        volatile String tldr;
        volatile String report;
        volatile long updatedAtEpoch;

        // -- transient (per process; the unit id is re-resolved after every restart) --
        volatile String unitId;
        /** Unit evidence version the current report reflects; -1 = unit not observed yet. */
        volatile long observedVersion = -1;
        /** Wall-clock ms unrevised evidence was first seen (0 = nothing pending) — the settle window. */
        volatile long changeSeenAtMs;
        volatile long lastRevisedAtMs;

        Entry(String id, String name, String tldr, String report,
                long createdAtEpoch, long updatedAtEpoch) {
            this.id = id;
            this.name = name;
            this.tldr = tldr == null ? "" : tldr;
            this.report = report == null ? "" : report;
            this.createdAtEpoch = createdAtEpoch;
            this.updatedAtEpoch = updatedAtEpoch;
        }
    }

    /**
     * Immutable view of one entry for the UI bridge — the persisted dossier plus
     * everything the mapped unit knows right now (price snapshot incl. the L&S
     * spark/history series, the first-mention price anchor, news, the last wire
     * lines, mention stats). All unit-derived fields are null/empty while unmapped.
     */
    public record EntryView(String id, String name, String tldr, String report,
            long createdAtEpoch, long updatedAtEpoch, boolean mapped, String ticker,
            String canonicalName, MarketSnapshot snapshot,
            Double firstPrice, Long firstPriceAtEpoch,
            List<RawNewsItem> news, List<SubjectUnit.UnitHeadline> recentHeadlines,
            int evidenceCount, Long lastActivityEpoch) {
    }

    /** One live subject offered as an add-suggestion (existing units, A-path). */
    public record SubjectOption(String name, String ticker) {
    }

    /** Refresh a mapped instrument's quote when it is older than this — watchlist-only freshness. */
    private static final long PRICE_REFRESH_MS = 180_000;

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private final SubjectRegistry subjectRegistry;
    private final AgentBrain brain;
    private final WatchlistStore store;
    private final ApplicationEventBus eventBus;
    private final ChatGateway chatGateway;
    /** The live EUR price chain (L&S-first) — optional, absent in tests. */
    private volatile de.bsommerfeld.wsbg.terminal.core.price.PriceSource priceSource;
    /** Wall-clock ms of the last refresh ATTEMPT per entry — also spaces out unpriceable subjects. */
    private final Map<String, Long> lastPriceRefreshMs = new java.util.concurrent.ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "watchlist");
                t.setDaemon(true);
                return t;
            });

    @Inject
    public WatchlistService(SubjectRegistry subjectRegistry, AgentBrain brain, LlmGate llmGate,
            WatchlistStore store, ApplicationEventBus eventBus) {
        this.subjectRegistry = subjectRegistry;
        this.brain = brain;
        this.store = store;
        this.eventBus = eventBus;
        this.chatGateway = new ChatGateway(brain, llmGate);

        for (WatchlistStore.PersistedEntry p : store.load()) {
            if (p.id() == null || p.name() == null || p.name().isBlank()) continue;
            entries.put(p.id(), new Entry(p.id(), p.name(), p.tldr(), p.report(),
                    p.createdAtEpoch(), p.updatedAtEpoch()));
        }
        scheduler.scheduleWithFixedDelay(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        LOG.info("WatchlistService started: {} persisted entrie(s), tick every {}s.",
                entries.size(), TICK_MS / 1000);
    }

    /**
     * Installs the live EUR price chain (L&S-first). Optional Guice method-injection
     * like {@code EditorialAgent}'s: present in production, absent in tests. Powers
     * the watchlist-only quote refresh — a QUIET watched subject would otherwise show
     * the price of its last pipeline attribution forever.
     */
    @com.google.inject.Inject(optional = true)
    void setPriceSource(de.bsommerfeld.wsbg.terminal.core.price.PriceSource priceSource) {
        this.priceSource = priceSource;
    }

    // -- public API (bridge) --

    /** Snapshot of all entries, insertion-ordered. */
    public synchronized List<EntryView> entries() {
        List<EntryView> out = new ArrayList<>(entries.size());
        for (Entry e : entries.values()) out.add(view(e));
        return out;
    }

    /**
     * Adds a subject by name (an existing unit's name/ticker OR a future name that
     * maps once the feed produces it). Room slang is canonicalized first, so
     * whatever the user types funnels to the same identity the pipeline uses.
     * Returns the created (or already-existing duplicate) entry.
     */
    public EntryView add(String rawName) {
        String name = rawName == null ? "" : rawName.strip();
        if (name.isEmpty() || name.length() > 80) return null;
        String canonical = WsbgJargon.canonicalize(name);
        synchronized (this) {
            for (Entry e : entries.values()) {
                if (NameMatcher.deUmlaut(e.name).equals(NameMatcher.deUmlaut(canonical))) {
                    return view(e); // duplicate — hand back the existing entry
                }
            }
            long now = Instant.now().getEpochSecond();
            Entry e = new Entry("wl-" + UUID.randomUUID().toString().substring(0, 8),
                    canonical, "", "", now, now);
            entries.put(e.id, e);
            persist();
            LOG.info("[WATCHLIST] added '{}' ({})", canonical, e.id);
            eventBus.post(new WatchlistChangedEvent());
            scheduler.execute(this::tick); // map + first report without waiting a full tick
            return view(e);
        }
    }

    /** Removes an entry (its dossier is gone for good). Returns whether it existed. */
    public synchronized boolean remove(String id) {
        Entry e = entries.remove(id);
        if (e == null) return false;
        persist();
        LOG.info("[WATCHLIST] removed '{}' ({})", e.name, id);
        eventBus.post(new WatchlistChangedEvent());
        return true;
    }

    /** Live subjects (most recently active first) as add-suggestions for the UI. */
    public List<SubjectOption> subjectOptions() {
        List<SubjectUnit> units = new ArrayList<>(subjectRegistry.all());
        units.sort(Comparator.comparing(SubjectUnit::lastActivity).reversed());
        List<SubjectOption> out = new ArrayList<>();
        for (SubjectUnit u : units) {
            if (out.size() >= 60) break;
            out.add(new SubjectOption(u.canonicalName(), u.ticker()));
        }
        return out;
    }

    // -- tick: map entries, detect fresh evidence, run due revisions --

    private void tick() {
        List<Entry> due = new ArrayList<>();
        boolean mappingChanged = false;
        long now = System.currentTimeMillis();
        synchronized (this) {
            for (Entry e : entries.values()) {
                SubjectUnit unit = e.unitId == null ? null : subjectRegistry.get(e.unitId);
                if (unit == null) {
                    // Unmapped (new entry / future name / unit merged away): try to latch on.
                    e.unitId = null;
                    unit = findUnitFor(e.name);
                    if (unit == null) continue;
                    e.unitId = unit.id;
                    mappingChanged = true;
                    // Baseline the version; anything the unit currently holds counts as
                    // unrevised material (first report, or a refresh after a restart).
                    e.observedVersion = unit.evidenceVersion();
                    e.changeSeenAtMs = e.report.isBlank() || unit.evidenceCount() > 0 ? now : 0;
                    LOG.info("[WATCHLIST] '{}' mapped to unit {} ({})", e.name, unit.id,
                            unit.canonicalName());
                }
                long v = unit.evidenceVersion();
                if (v != e.observedVersion && e.changeSeenAtMs == 0) {
                    e.changeSeenAtMs = now; // fresh evidence — start the settle window
                }
                boolean initial = e.report.isBlank();
                long settle = initial ? 0 : SETTLE_MS;
                long cooldown = initial ? 0 : COOLDOWN_MS;
                if (e.changeSeenAtMs > 0
                        && now - e.changeSeenAtMs >= settle
                        && now - e.lastRevisedAtMs >= cooldown) {
                    due.add(e);
                }
            }
        }
        if (mappingChanged) eventBus.post(new WatchlistChangedEvent());
        refreshOnePrice(now);
        for (Entry e : due) {
            try {
                revise(e);
            } catch (Exception ex) {
                LOG.warn("[WATCHLIST] revision for '{}' failed: {}", e.name, ex.getMessage());
            }
        }
    }

    /**
     * Watchlist-only quote freshness: at most ONE instrument per tick (≈4 venue
     * calls/min at the 15 s cadence, trivially light) gets a fresh EUR snapshot
     * from the price chain when its current quote is older than
     * {@link #PRICE_REFRESH_MS}. The refresh rides {@link SubjectUnit#updateResolved}
     * with the unit's own identity (ISIN-exact on L&S when stamped), so the wire's
     * next line benefits too; the evidence version is untouched — a price move
     * alone never triggers a dossier revision or a compose.
     */
    private void refreshOnePrice(long now) {
        de.bsommerfeld.wsbg.terminal.core.price.PriceSource source = priceSource;
        if (source == null) return;
        Entry pick = null;
        SubjectUnit pickUnit = null;
        long oldestAttempt = Long.MAX_VALUE;
        synchronized (this) {
            for (Entry e : entries.values()) {
                SubjectUnit unit = e.unitId == null ? null : subjectRegistry.get(e.unitId);
                if (unit == null || !unit.isInstrument()) continue;
                MarketSnapshot s = unit.snapshot();
                boolean fresh = s != null && s.marketTimeEpochSeconds() > 0
                        && now / 1000 - s.marketTimeEpochSeconds() < PRICE_REFRESH_MS / 1000;
                long lastAttempt = lastPriceRefreshMs.getOrDefault(e.id, 0L);
                if (fresh || now - lastAttempt < PRICE_REFRESH_MS) continue;
                if (lastAttempt < oldestAttempt) {
                    oldestAttempt = lastAttempt;
                    pick = e;
                    pickUnit = unit;
                }
            }
        }
        if (pick == null) return;
        lastPriceRefreshMs.put(pick.id, now);
        try {
            var ref = new de.bsommerfeld.wsbg.terminal.core.price.PriceRef(
                    pickUnit.canonicalName(), pickUnit.ticker(), pickUnit.isin());
            var snapshot = source.snapshot(ref);
            if (snapshot.isPresent()) {
                pickUnit.updateResolved(null, pickUnit.ticker(), snapshot.get(), List.of());
                eventBus.post(new WatchlistChangedEvent());
            }
        } catch (Exception ex) {
            LOG.debug("[WATCHLIST] price refresh for '{}' failed: {}", pick.name, ex.getMessage());
        }
    }

    /**
     * One dossier revision: the standing report + the unit's CURRENT full state go to
     * the model ("this is your analysis — how does it change with this evidence?"),
     * the revised {tldr, report} comes back. Serialized on the single tick thread and
     * gated by the shared LLM semaphore, so the watchlist never floods the model.
     */
    private void revise(Entry e) {
        SubjectUnit unit = e.unitId == null ? null : subjectRegistry.get(e.unitId);
        if (unit == null) return;
        ChatModel model = brain.getAgentModel();
        if (model == null) return;

        long version = unit.evidenceVersion(); // captured BEFORE — mid-call evidence re-fires later
        String lang = brain.getUserLanguage().code();
        String sys = PromptLoader.loadLocalized("watchlist-report", lang)
                .replace("{{LANGUAGE}}", brain.getUserLanguage().displayName());
        String user = revisionBrief(e, unit);

        long t0 = System.nanoTime();
        String raw = chatGateway.chat(model, sys, user);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        String tldr = null;
        String report = null;
        JsonNode json = JsonReplies.parseJson(raw);
        if (json != null) {
            tldr = json.path("tldr").asText(null);
            report = json.path("report").asText(null);
        }
        if (tldr == null) tldr = JsonReplies.regexStringField(raw, "tldr");
        if (report == null) report = JsonReplies.regexStringField(raw, "report");

        // Every outcome stamps the version — a whiff waits for genuinely new evidence
        // instead of busy-looping the model on the same material (compose-path rule).
        e.observedVersion = version;
        e.changeSeenAtMs = 0;
        e.lastRevisedAtMs = System.currentTimeMillis();

        if (report == null || report.isBlank()) {
            String head = raw == null ? "" : raw.strip();
            LOG.warn("[WATCHLIST] no usable report for '{}' ({} ms) — raw reply: {}", e.name, ms,
                    head.length() > 300 ? head.substring(0, 300) + "…" : head);
            return;
        }
        String newReport = cap(report.strip(), MAX_REPORT_CHARS);
        String newTldr = cap(tldr == null ? "" : tldr.strip().replace('\n', ' '), MAX_TLDR_CHARS);
        boolean changed = !newReport.equals(e.report) || !newTldr.equals(e.tldr);
        e.report = newReport;
        e.tldr = newTldr;
        if (changed) {
            e.updatedAtEpoch = Instant.now().getEpochSecond();
            synchronized (this) {
                persist();
            }
            eventBus.post(new WatchlistChangedEvent());
        }
        LOG.info("[WATCHLIST] '{}' revised in {} ms ({} chars{})", e.name, ms,
                newReport.length(), changed ? "" : ", unchanged");
    }

    /** The revision brief: dated market/news/room state + the standing dossier to edit. */
    private String revisionBrief(Entry e, SubjectUnit unit) {
        Instant now = Instant.now();
        StringBuilder sb = new StringBuilder();
        sb.append("NOW: ").append(STAMP.format(now)).append('\n');
        sb.append("SUBJECT: ").append(unit.canonicalName());
        if (unit.isInstrument()) sb.append(" (Ticker ").append(unit.ticker()).append(')');
        sb.append('\n');

        MarketSnapshot s = unit.snapshot();
        if (s != null && s.hasPrice()) {
            sb.append("MARKET: ").append(String.format(java.util.Locale.ROOT, "%.2f", s.price()));
            if ("PTS".equals(s.currency())) sb.append(" points (index)");
            else if (s.currency() != null && !s.currency().isEmpty()) sb.append(' ').append(s.currency());
            if (Double.isFinite(s.dayChangePercent())) {
                sb.append(String.format(java.util.Locale.ROOT, ", day %+.2f%%", s.dayChangePercent()));
            }
            sb.append('\n');
        }

        List<RawNewsItem> news = unit.news();
        if (!news.isEmpty()) {
            sb.append("NEWS (verified):\n");
            int n = 0;
            for (RawNewsItem item : news) {
                if (++n > MAX_NEWS_IN_BRIEF) break;
                sb.append("  - ");
                if (item.publishedAt() != null) sb.append('[').append(STAMP.format(item.publishedAt())).append("] ");
                sb.append(item.title());
                if (item.publisher() != null && !item.publisher().isEmpty()) {
                    sb.append(" · ").append(item.publisher());
                }
                sb.append('\n');
            }
        }

        // ALL current evidence — deliberately NO covered/uncovered boundary (unlike the
        // compose brief): the dossier aggregates everything the unit knows right now.
        List<SubjectUnit.EvidenceRef> evidence = unit.evidence();
        int start = evidence.size();
        int budget = EVIDENCE_CHAR_BUDGET;
        while (start > 0 && budget - evidence.get(start - 1).snippet().length() - 24 >= 0) {
            start--;
            budget -= evidence.get(start).snippet().length() + 24;
        }
        sb.append("ROOM EVIDENCE (r/wallstreetbetsGER, unverified):\n");
        if (evidence.isEmpty()) sb.append("  (none in the current window)\n");
        if (start > 0) sb.append("  (").append(start).append(" older mentions omitted)\n");
        for (SubjectUnit.EvidenceRef ref : evidence.subList(start, evidence.size())) {
            sb.append("  - [").append(STAMP.format(Instant.ofEpochSecond(ref.addedAtEpoch())))
                    .append("] ").append(ref.snippet()).append('\n');
        }

        List<SubjectUnit.UnitHeadline> headlines = unit.headlines();
        if (!headlines.isEmpty()) {
            sb.append("WIRE LINES ALREADY PUBLISHED FOR THIS SUBJECT:\n");
            int from = Math.max(0, headlines.size() - MAX_HEADLINES_IN_BRIEF);
            if (from > 0) sb.append("  (").append(from).append(" earlier lines omitted)\n");
            for (SubjectUnit.UnitHeadline h : headlines.subList(from, headlines.size())) {
                sb.append("  - [").append(STAMP.format(Instant.ofEpochSecond(h.atEpoch())))
                        .append("] ").append(h.text()).append('\n');
            }
        }

        sb.append("\nCURRENT DOSSIER (this is your analysis — revise it):\n");
        sb.append(e.report.isBlank() ? "(none yet — write the first version)" : e.report).append('\n');
        return sb.toString();
    }

    // -- mapping --

    /**
     * Deterministic entry→unit binding: exact ticker/name equality first, then a
     * significant-word subset in either direction ("Rheinmetall" ⊂ "Rheinmetall AG").
     * Among several candidates the instrument-backed unit with the most evidence
     * wins. Conservative on purpose — a wrong latch would feed a foreign story into
     * the dossier; an unmapped entry just retries next tick.
     */
    private SubjectUnit findUnitFor(String name) {
        Set<String> nameWords = NameMatcher.significantWords(name);
        SubjectUnit best = null;
        int bestScore = -1;
        for (SubjectUnit u : subjectRegistry.all()) {
            int score = -1;
            if (name.equalsIgnoreCase(u.ticker()) || name.equalsIgnoreCase(u.canonicalName())) {
                score = 1_000_000;
            } else if (!nameWords.isEmpty()) {
                Set<String> unitWords = NameMatcher.significantWords(u.canonicalName());
                boolean subset = !unitWords.isEmpty()
                        && (unitWords.containsAll(nameWords) || nameWords.containsAll(unitWords));
                if (subset) score = 0;
            }
            if (score < 0) continue;
            score += (u.isInstrument() ? 100_000 : 0) + Math.min(u.evidenceCount(), 10_000);
            if (score > bestScore) {
                bestScore = score;
                best = u;
            }
        }
        return best;
    }

    // -- helpers --

    /** How many news / prior wire lines ride along per entry view. */
    private static final int VIEW_NEWS = 6;
    private static final int VIEW_HEADLINES = 3;

    private EntryView view(Entry e) {
        SubjectUnit unit = e.unitId == null ? null : subjectRegistry.get(e.unitId);
        if (unit == null) {
            return new EntryView(e.id, e.name, e.tldr, e.report, e.createdAtEpoch,
                    e.updatedAtEpoch, false, null, null, null, null, null,
                    List.of(), List.of(), 0, null);
        }
        List<RawNewsItem> news = unit.news();
        if (news.size() > VIEW_NEWS) news = news.subList(0, VIEW_NEWS);
        List<SubjectUnit.UnitHeadline> headlines = unit.headlines();
        if (headlines.size() > VIEW_HEADLINES) {
            headlines = headlines.subList(headlines.size() - VIEW_HEADLINES, headlines.size());
        }
        return new EntryView(e.id, e.name, e.tldr, e.report, e.createdAtEpoch, e.updatedAtEpoch,
                true, unit.ticker(), unit.canonicalName(), unit.snapshot(),
                unit.firstPrice(),
                unit.firstPriceAt() == null ? null : unit.firstPriceAt().getEpochSecond(),
                List.copyOf(news), List.copyOf(headlines),
                unit.evidenceCount(), unit.lastActivity().getEpochSecond());
    }

    /** Persists the durable half of every entry. Caller holds the service monitor. */
    private void persist() {
        List<WatchlistStore.PersistedEntry> out = new ArrayList<>(entries.size());
        for (Entry e : entries.values()) {
            out.add(new WatchlistStore.PersistedEntry(e.id, e.name, e.tldr, e.report,
                    e.createdAtEpoch, e.updatedAtEpoch));
        }
        store.save(Collections.unmodifiableList(out));
    }

    private static String cap(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1).stripTrailing() + "…";
    }
}
