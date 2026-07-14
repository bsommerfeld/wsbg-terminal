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
 * <p><b>Simplified to price + room (2026-07-13, user mandate):</b> the watchlist
 * answers two questions per subject — what does it COST (minute-fresh L&S quote)
 * and what does the ROOM say (the dossier, revised on room evidence only). The
 * deep data legs (profile, analysts, insiders, shorts, fundamentals) moved into
 * the on-demand KI-DD tool ({@link DeepDiveService}) — one fixed report instead
 * of a continuously fattened dossier.
 *
 * <p>Mapping: an entry names a subject; it binds to a live unit deterministically
 * (ticker/name equality, then significant-word subset). An entry whose subject the
 * feed hasn't produced yet is <b>researched by the watchlist itself</b> (2026-07-12):
 * the name goes through the pipeline's own fully-wired {@link TickerResolver}
 * (identity desk, price chain, news pool) and the result is seeded into the
 * {@link SubjectRegistry} as a normal unit with zero room evidence — identity and
 * price work without a single Käfig mention; the dossier waits for the room. The
 * seed never marks the unit dirty: the wire stays room-driven. If the room mentions
 * the subject later, the attributor's findOrCreate hits the SAME identity key and
 * the evidence folds into this very unit.
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
    /** A subject the feed doesn't know is researched (full resolver pass) at most this often. */
    private static final long RESEARCH_RETRY_MS = 15 * 60 * 1000L;
    /** Evidence-independent news refresh per mapped entry — context for the UI + the wire's units. */
    private static final long NEWS_REFRESH_MS = 10 * 60 * 1000L;
    /** News items asked from the pool per refresh (the unit merges by id and caps itself). */
    private static final int NEWS_FETCH_COUNT = 6;
    /**
     * Only news at most this old reach the UI view, the revision brief and the
     * change detection — the dossier must be CURRENT (user mandate 2026-07-12: a
     * search hit from last year must never masquerade as the subject's news).
     * Undated items are kept (can't be judged); the unit itself keeps its full
     * merged set, the cut is a watchlist reading rule.
     */
    private static final long NEWS_MAX_AGE_DAYS = 30;

    /** Char budget for the room-evidence block of the revision brief (newest kept). */
    private static final int EVIDENCE_CHAR_BUDGET = 4000;
    private static final int MAX_NEWS_IN_BRIEF = 8;
    private static final int MAX_HEADLINES_IN_BRIEF = 6;
    /** Hard caps on the model's reply — a 4B occasionally rambles; the dossier stays compact. */
    private static final int MAX_REPORT_CHARS = 2000;
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

    /**
     * Watchlist quote cadence: every mapped instrument gets a fresh L&S snapshot
     * plus Tradegate venue stats about once a MINUTE (user-mandated 2026-07-10 —
     * the watchlist is the one place the user actively stares at a price).
     */
    private static final long PRICE_REFRESH_MS = 60_000;
    /** Refresh at most this many instruments per 15 s tick — spreads a long list. */
    private static final int MAX_PRICE_REFRESH_PER_TICK = 4;

    /**
     * Feature held back for a later release (2026-07-14): the widget is hidden from the
     * grid, and the background loop must not run — no ticks, no price/news refreshes,
     * no research resolves, no LLM revisions. The service stays constructible because
     * the KI-DD borrows the subject suggestions through the watchlist bridge and the
     * weather report optional-injects it (an inert service answers snapshot-less
     * entries, which {@code watchlistMoves} already filters out). Flip to false to
     * re-arm the loop when the watchlist ships.
     */
    private static final boolean HELD_BACK = true;

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
    /** Owner of the fully-wired TickerResolver (research leg) — optional, absent in tests. */
    private volatile EditorialAgent editorialAgent;
    /** Multi-source news pool for the evidence-independent refresh — optional, absent in tests. */
    private volatile de.bsommerfeld.wsbg.terminal.aggregator.NewsAggregator newsAggregator;
    /** Wall-clock ms of the last research ATTEMPT per entry — a miss re-tries after the backoff. */
    private final Map<String, Long> lastResearchAttemptMs = new java.util.concurrent.ConcurrentHashMap<>();
    /** Wall-clock ms of the last news-pool refresh per entry. */
    private final Map<String, Long> lastNewsRefreshMs = new java.util.concurrent.ConcurrentHashMap<>();

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
        if (HELD_BACK) {
            LOG.info("WatchlistService held back: {} persisted entrie(s), background loop disabled.",
                    entries.size());
        } else {
            scheduler.scheduleWithFixedDelay(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
            LOG.info("WatchlistService started: {} persisted entrie(s), tick every {}s.",
                    entries.size(), TICK_MS / 1000);
        }
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

    /**
     * Installs the editorial agent — solely as the owner of the process's ONE
     * fully-wired {@link TickerResolver} (identity desk, judge, corpus, price
     * chain, news pool), which the research leg shares so a watched subject the
     * room never mentioned resolves through exactly the same identity machinery
     * as a pipeline subject. Optional like the other legs: present in production,
     * absent in tests, where entries simply stay unmapped until the feed produces
     * their unit (the pre-research behaviour).
     */
    @com.google.inject.Inject(optional = true)
    void setEditorialAgent(EditorialAgent editorialAgent) {
        this.editorialAgent = editorialAgent;
    }

    /**
     * Installs the multi-source news pool (Yahoo by symbol + the German
     * name-addressed venues). Optional like the other legs. Powers the
     * evidence-independent news refresh — without it a room-quiet subject's news
     * would only ever update when the pipeline happens to re-resolve it.
     */
    @com.google.inject.Inject(optional = true)
    void setNewsAggregator(de.bsommerfeld.wsbg.terminal.aggregator.NewsAggregator aggregator) {
        this.newsAggregator = aggregator;
    }

    // -- public API (bridge) --

    /** Snapshot of all entries, insertion-ordered. */
    public synchronized List<EntryView> entries() {
        List<EntryView> out = new ArrayList<>(entries.size());
        for (Entry e : entries.values()) out.add(view(e));
        return out;
    }

    /**
     * Adds a subject by name (an existing unit's name/ticker OR a name the feed
     * has never produced — the research leg then resolves and seeds it itself).
     * Room slang is canonicalized first, so whatever the user types funnels to
     * the same identity the pipeline uses. Returns the created (or
     * already-existing duplicate) entry.
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
            if (!HELD_BACK) scheduler.execute(this::tick); // map + first report without waiting a full tick
            return view(e);
        }
    }

    /** Removes an entry (its dossier is gone for good). Returns whether it existed. */
    public synchronized boolean remove(String id) {
        Entry e = entries.remove(id);
        if (e == null) return false;
        lastPriceRefreshMs.remove(id);
        lastResearchAttemptMs.remove(id);
        lastNewsRefreshMs.remove(id);
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
        long now = System.currentTimeMillis();
        researchOneUnmapped(now);
        List<Entry> due = new ArrayList<>();
        boolean mappingChanged = false;
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
                    // Whatever ROOM evidence the unit holds is unrevised material;
                    // a room-quiet unit has nothing for the dossier to say yet.
                    e.changeSeenAtMs = unit.evidenceCount() > 0 ? now : 0;
                    LOG.info("[WATCHLIST] '{}' mapped to unit {} ({})", e.name, unit.id,
                            unit.canonicalName());
                }
                // Revision material is ROOM EVIDENCE only (simplified 2026-07-13):
                // the dossier answers "was sagt der Raum" — news and price move the
                // UI, never the model.
                if (unit.evidenceVersion() != e.observedVersion && e.changeSeenAtMs == 0) {
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
        refreshPrices(now);
        refreshNews(now);
        for (Entry e : due) {
            try {
                revise(e);
            } catch (Exception ex) {
                LOG.warn("[WATCHLIST] revision for '{}' failed: {}", e.name, ex.getMessage());
            }
        }
    }

    /**
     * The minute cadence for every mapped instrument: each entry whose last refresh
     * attempt is older than {@link #PRICE_REFRESH_MS} gets a fresh EUR snapshot
     * from the price chain when its quote has actually aged past the window.
     * Capped at {@link #MAX_PRICE_REFRESH_PER_TICK} per 15 s tick (price-less
     * instruments first, then oldest attempt), so a long list degrades to a
     * slightly slower carousel instead of a burst. The snapshot refresh rides
     * {@link SubjectUnit#updateResolved} with the unit's own identity (ISIN-exact
     * on L&S when stamped), so the wire's next line benefits too; the evidence
     * version is untouched — a price move alone never triggers a dossier revision
     * or a compose.
     */
    private void refreshPrices(long now) {
        de.bsommerfeld.wsbg.terminal.core.price.PriceSource source = priceSource;
        if (source == null) return;
        record Due(Entry entry, SubjectUnit unit, long lastAttempt) {}
        List<Due> due = new ArrayList<>();
        synchronized (this) {
            for (Entry e : entries.values()) {
                SubjectUnit unit = e.unitId == null ? null : subjectRegistry.get(e.unitId);
                if (unit == null || !unit.isInstrument()) continue;
                long lastAttempt = lastPriceRefreshMs.getOrDefault(e.id, 0L);
                if (now - lastAttempt < PRICE_REFRESH_MS) continue;
                due.add(new Due(e, unit, lastAttempt));
            }
        }
        // Price-less instruments jump the queue (a watched name must never sit
        // without a quote longer than necessary); within each group oldest first.
        due.sort(Comparator
                .comparing((Due d) -> d.unit().snapshot() != null && d.unit().snapshot().hasPrice())
                .thenComparingLong(Due::lastAttempt));
        boolean changed = false;
        for (Due d : due.subList(0, Math.min(due.size(), MAX_PRICE_REFRESH_PER_TICK))) {
            lastPriceRefreshMs.put(d.entry.id, now);
            MarketSnapshot s = d.unit.snapshot();
            boolean quoteFresh = s != null && s.marketTimeEpochSeconds() > 0
                    && now / 1000 - s.marketTimeEpochSeconds() < PRICE_REFRESH_MS / 1000;
            if (!quoteFresh) {
                try {
                    var ref = new de.bsommerfeld.wsbg.terminal.core.price.PriceRef(
                            d.unit.canonicalName(), d.unit.ticker(), d.unit.isin());
                    var snapshot = source.snapshot(ref);
                    if (snapshot.isPresent()) {
                        d.unit.updateResolved(null, d.unit.ticker(), snapshot.get(), List.of());
                        changed = true;
                    }
                } catch (Exception ex) {
                    LOG.debug("[WATCHLIST] price refresh for '{}' failed: {}",
                            d.entry.name, ex.getMessage());
                }
            }
        }
        if (changed) eventBus.post(new WatchlistChangedEvent());
    }

    /**
     * The research leg (2026-07-12): a watched subject the feed has never produced
     * is resolved by the watchlist ITSELF — the entry's name goes through the same
     * fully-wired {@link TickerResolver} the pipeline uses (identity desk, judge,
     * price chain, news pool), and the verdict is seeded into the
     * {@link SubjectRegistry} as a normal unit with ZERO room evidence — identity
     * and the minute quote work without a single mention; the dossier waits for
     * the room. When the room mentions the subject later the attributor's
     * findOrCreate hits the SAME identity key, so the evidence folds into this
     * very unit. The seed never marks the unit dirty — the wire stays room-driven;
     * a researched subject never composes a headline out of thin air.
     *
     * <p>At most ONE subject per tick; the resolve (network + judge call) runs
     * OUTSIDE the service monitor; a miss or a Yahoo throttle re-tries after
     * {@link #RESEARCH_RETRY_MS}.
     */
    private void researchOneUnmapped(long now) {
        EditorialAgent agent = editorialAgent;
        if (agent == null) return;
        Entry pick = null;
        synchronized (this) {
            for (Entry e : entries.values()) {
                if (e.unitId != null && subjectRegistry.get(e.unitId) != null) continue;
                if (findUnitFor(e.name) != null) continue; // a live unit exists — the mapping loop latches
                if (now - lastResearchAttemptMs.getOrDefault(e.id, 0L) < RESEARCH_RETRY_MS) continue;
                pick = e;
                break;
            }
        }
        if (pick == null) return;
        lastResearchAttemptMs.put(pick.id, now);
        TickerResolver.ResolvedSubject rs;
        try {
            rs = agent.tickerResolver().resolve(pick.name, 0);
        } catch (Exception ex) {
            LOG.warn("[WATCHLIST] research for '{}' failed: {}", pick.name, ex.getMessage());
            return;
        }
        if (rs == null || rs.unresolved()) return; // Yahoo throttled — re-tried after the backoff
        String key = SubjectAttributor.unitKey(rs);
        if (key == null) return;
        synchronized (this) {
            if (!entries.containsKey(pick.id)) return; // removed while researching
            SubjectUnit unit = subjectRegistry.findOrCreate(key, rs.canonicalName());
            unit.updateResolved(rs.canonicalName(), rs.ticker(), rs.snapshot(), rs.news());
            unit.noteIsin(rs.isin());
            pick.unitId = unit.id;
            pick.observedVersion = unit.evidenceVersion();
            // Only ROOM evidence is dossier material; a freshly researched unit
            // usually has none — the entry then shows price + "Raum still".
            pick.changeSeenAtMs = unit.evidenceCount() > 0 ? now : 0;
            LOG.info("[WATCHLIST] '{}' researched → unit {} ({}{})", pick.name, unit.id,
                    rs.isInstrument() ? "instrument " + rs.ticker() : "theme/name",
                    rs.hasNews() ? ", " + rs.news().size() + " news" : "");
        }
        eventBus.post(new WatchlistChangedEvent());
    }

    /**
     * The evidence-independent news leg: every mapped entry re-asks the
     * multi-source news pool about once every {@link #NEWS_REFRESH_MS} — by ticker
     * AND canonical name, exactly like the pipeline's resolver — and merges the
     * result into the unit (merge by id, never replace, unit-capped). Context for
     * the UI's News list and the unit the wire composes from; deliberately NOT a
     * dossier trigger (the dossier is room-only since the 2026-07-13
     * simplification). One entry per tick (oldest refresh first) — a long list
     * becomes a slow carousel, never a burst.
     */
    private void refreshNews(long now) {
        de.bsommerfeld.wsbg.terminal.aggregator.NewsAggregator aggregator = newsAggregator;
        if (aggregator == null) return;
        Entry pick = null;
        SubjectUnit pickUnit = null;
        long oldest = Long.MAX_VALUE;
        synchronized (this) {
            for (Entry e : entries.values()) {
                SubjectUnit unit = e.unitId == null ? null : subjectRegistry.get(e.unitId);
                if (unit == null) continue;
                long last = lastNewsRefreshMs.getOrDefault(e.id, 0L);
                if (now - last < NEWS_REFRESH_MS || last >= oldest) continue;
                oldest = last;
                pick = e;
                pickUnit = unit;
            }
        }
        if (pick == null) return;
        lastNewsRefreshMs.put(pick.id, now);
        try {
            List<RawNewsItem> fresh = aggregator.newsFor(
                    pickUnit.ticker(), pickUnit.canonicalName(), NEWS_FETCH_COUNT);
            // The freshness cut applies at the door too: a year-old search hit
            // must not even enter the unit through the watchlist's own leg.
            Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(NEWS_MAX_AGE_DAYS));
            fresh = fresh.stream()
                    .filter(n -> n.publishedAt() == null || !n.publishedAt().isBefore(cutoff))
                    .toList();
            if (fresh.isEmpty()) return;
            int before = freshNews(pickUnit).size();
            pickUnit.updateResolved(null, pickUnit.ticker(), null, fresh);
            // Warm the article-digest cache for these items (async, digester's own
            // worker): the wire's next compose over this unit then carries the
            // articles' substance, not just titles — the resolver only prefetches
            // items IT fetched, never the watchlist's own leg.
            EditorialAgent agent = editorialAgent;
            if (agent != null) agent.newsDigester().prefetch(fresh);
            if (freshNews(pickUnit).size() != before) {
                eventBus.post(new WatchlistChangedEvent());
            }
        } catch (Exception ex) {
            LOG.debug("[WATCHLIST] news refresh for '{}' failed: {}", pick.name, ex.getMessage());
        }
    }

    /**
     * The unit's news through the watchlist's freshness cut ({@link #NEWS_MAX_AGE_DAYS}):
     * a stale search hit stays in the unit (the wire may still cite it) but never
     * reaches the watchlist view, the revision brief or the change detection.
     */
    private static List<RawNewsItem> freshNews(SubjectUnit unit) {
        Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(NEWS_MAX_AGE_DAYS));
        List<RawNewsItem> out = new ArrayList<>();
        for (RawNewsItem n : unit.news()) {
            if (n.publishedAt() == null || !n.publishedAt().isBefore(cutoff)) out.add(n);
        }
        return out;
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
        // The roomy dossier handle — the agent model's 768-token backstop would
        // truncate a sectioned report mid-heading. Agent model as test fallback.
        ChatModel model = brain.getDossierModel() != null
                ? brain.getDossierModel() : brain.getAgentModel();
        if (model == null) return;

        // Captured BEFORE the model call — evidence arriving mid-call re-fires later.
        long version = unit.evidenceVersion();
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

        List<RawNewsItem> news = freshNews(unit);
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
        List<RawNewsItem> news = freshNews(unit);
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
