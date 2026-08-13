package de.bsommerfeld.wsbg.terminal.agent.tagging;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.instruments.AliasCandidate;
import de.bsommerfeld.wsbg.terminal.instruments.AliasStore;
import de.bsommerfeld.wsbg.terminal.instruments.InstrumentCorpus;
import de.bsommerfeld.wsbg.terminal.instruments.InstrumentEntry;
import de.bsommerfeld.wsbg.terminal.instruments.NameKey;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.pool.ArticleTagger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * The article→instrument judgment engine — the replacement for the pool's old
 * query-time any-word guess ("Canopy Growth" caught every growth story).
 * Judged ONCE per article-and-instrument, answered as a set lookup after.
 *
 * <h3>The staged pipeline (each stage confirms, rejects or hands on)</h3>
 * <ol>
 *   <li><b>Hard keys</b> — validated ISIN (field or verbatim text), source
 *       ticker tags, cashtags, exchange-paren tags. Format, not lists.</li>
 *   <li><b>Name phrase</b> — the multi-word name IN ORDER within a small slop
 *       (Lucene {@code MultiPhraseQuery}); "World Trade Center" no longer
 *       reaches "The Trade Desk" because "desk" never follows.</li>
 *   <li><b>Discriminance</b> — a single name word may carry a match only when
 *       the universe statistics say it identifies (entity-DF over ~15k corpus
 *       names replaces every stop-word list) and the basin statistics say it
 *       is not a flood word.</li>
 *   <li><b>Sense</b> — NPMI collocate clusters + the compound test decide
 *       whether the word is AMBIGUOUS in the current basin ("gold" ↔
 *       silber/unze vs. medaille/olympia; "Sofi-Brille"). Ambiguous hits are
 *       confirmed against the instrument's self-supervised context profile
 *       (seeded by stages 1-2), or handed to the arbiter.</li>
 *   <li><b>Arbiter (LLM)</b> — one cached verdict per (instrument, sense
 *       cluster), never per article. The rest of the pipeline is
 *       deterministic and unit-testable.</li>
 * </ol>
 *
 * <p><b>Salience:</b> a title where the instrument stands beside ANOTHER
 * identifying entity, for a name whose basin-wide coverage is dominated by
 * exactly that pattern (analyst attribution — "Deutsche Bank Research belässt
 * Tui auf 'Buy'"), is judged {@code MENTIONED}, not {@code SUBJECT}; only
 * subjects answer an instrument query. The attribution pattern is learned
 * from the basin's own distribution, not from a list of banks.
 *
 * <p><b>Universe changes need no replay:</b> judging is anchored to the
 * standing index, so the first query for a newly known instrument IS its
 * retro pass over every pooled article.
 *
 * <p><b>Concurrency:</b> called from pool query threads and the pour path;
 * all state is concurrent, ingestion is drained under one internal lock, and
 * the engine NEVER calls back into the pool — no lock order exists between
 * the two. Arbiter calls run on their own single thread so a slow model can
 * never stall ingestion.
 */
@Singleton
public final class LuceneArticleTagger implements ArticleTagger, Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(LuceneArticleTagger.class);

    /**
     * Entity-DF (distinct names) at or below which a token identifies an
     * instrument on its own. Measured against the real corpus: robinhood 3,
     * apple 3, sofi 2, nvidia 2, tui 1 — while deutsche 8, markets 16,
     * growth 21, bank/gold/technology 130+ stay out. Common words that slip
     * under this bar ("trade": 1 name) are caught by the case statistics
     * ({@link SenseStats.Sense#commonWordUsage()}).
     */
    static final int IDENTIFYING_ENTITY_DF_MAX = 4;
    /** Entity-DF at or above which a name token is generic (the learned "stop word"). */
    static final int GENERIC_ENTITY_DF = 12;
    /** Basin share above which even a unique token is a flood word → ambiguity path. */
    static final double LONE_TOKEN_BASIN_RATIO_MAX = 0.10;
    /** Phrase window: name tokens must appear in order within this many gaps. */
    static final int PHRASE_SLOP = 2;
    /** Compound-binding share above which a token's basin usage is a foreign compound. */
    static final double COMPOUND_BOUND_RATIO = 0.6;
    /** Attribution demotion: share of multi-entity titles among the name's title hits… */
    static final double ATTRIBUTOR_RATE = 0.7;
    /** …and how many title hits that reading needs before it may demote anything. */
    static final int ATTRIBUTOR_MIN_OBS = 8;
    /** Sense-statistics staleness: recompute when the basin grew/shrank this much. */
    private static final int SENSE_STALE_DOCS = 300;

    private final ArticleIndex index = new ArticleIndex();
    private final Map<String, DocRecord> docs = new ConcurrentHashMap<>();
    /** {@code isin:…}/{@code sym:…} → identities of articles declaring that key. */
    private final Map<String, Set<String>> hardMentions = new ConcurrentHashMap<>();

    private final Queue<Article> pendingAdds = new ConcurrentLinkedQueue<>();
    private final Queue<String> pendingRemovals = new ConcurrentLinkedQueue<>();
    private final Object drainLock = new Object();

    private final ExecutorService ingestWorker =
            Executors.newSingleThreadExecutor(r -> daemon(r, "article-tagger"));
    private final ExecutorService arbiterWorker =
            Executors.newSingleThreadExecutor(r -> daemon(r, "article-tagger-arbiter"));

    private final Supplier<List<InstrumentEntry>> universeSource;
    private final AliasStore aliases;
    private volatile TagArbiter arbiter;

    private volatile UniverseStats universe = UniverseStats.EMPTY;
    private volatile int universeSeenSize = -1;

    /** Learned spelling → symbol memory, reversed: base symbol → live spellings. */
    private volatile Map<String, List<String>> aliasSpellings = Map.of();
    private volatile int aliasSeenVersion = -1;

    private final Map<String, SenseStats.Sense> senseCache = new ConcurrentHashMap<>();
    /** (canonKey + '|' + cluster signature) → the arbitrated sense verdict. */
    private final Map<String, Boolean> senseVerdicts = new ConcurrentHashMap<>();
    private final Set<String> senseQueued = ConcurrentHashMap.newKeySet();

    /** Any key an instrument was ever queried under → its canonical key. */
    private final Map<String, String> keyAlias = new ConcurrentHashMap<>();

    @Inject
    public LuceneArticleTagger(InstrumentCorpus corpus, AliasStore aliases) {
        this(corpus::entries, aliases);
    }

    /** Test seam: an explicit universe, an optional alias memory. */
    public LuceneArticleTagger(Supplier<List<InstrumentEntry>> universeSource, AliasStore aliases) {
        this.universeSource = universeSource;
        this.aliases = aliases;
    }

    /** Installs the sense arbiter (the model's seat). Absent = deterministic only. */
    public void setArbiter(TagArbiter arbiter) {
        this.arbiter = arbiter;
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    // ------------------------------------------------------------------ pour side

    @Override
    public void ingest(Collection<Article> articles) {
        if (articles == null || articles.isEmpty()) return;
        for (Article a : articles) {
            if (a != null) pendingAdds.add(a);
        }
        kick();
    }

    @Override
    public void forget(Collection<String> identities) {
        if (identities == null || identities.isEmpty()) return;
        for (String id : identities) {
            if (id != null && !id.isBlank()) pendingRemovals.add(id);
        }
        kick();
    }

    private void kick() {
        try {
            ingestWorker.submit(this::drain);
        } catch (RejectedExecutionException closed) {
            // shutting down — whatever is queued stays unjudged, deliberately
        }
    }

    /**
     * Folds every queued add/remove into index + records. Runs on the ingest
     * worker AND synchronously at the head of a query (so a query never races
     * its own pour); the lock makes both orders safe.
     */
    private void drain() {
        synchronized (drainLock) {
            boolean changed = false;
            try {
                Article a;
                while ((a = pendingAdds.poll()) != null) {
                    String id = a.identity();
                    if (id.isEmpty() || docs.containsKey(id)) continue;
                    DocRecord r = new DocRecord(a);
                    docs.put(id, r);
                    index.add(id, DocRecord.indexText(a));
                    for (String key : r.hardKeys) {
                        hardMentions.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(id);
                    }
                    changed = true;
                }
                List<String> removals = new ArrayList<>();
                String id;
                while ((id = pendingRemovals.poll()) != null) {
                    DocRecord r = docs.remove(id);
                    if (r == null) continue;
                    for (String key : r.hardKeys) {
                        Set<String> ids = hardMentions.get(key);
                        if (ids != null) ids.remove(id);
                    }
                    removals.add(id);
                }
                if (!removals.isEmpty()) {
                    index.delete(removals);
                    changed = true;
                }
            } catch (IOException e) {
                LOG.warn("article tagger ingest failed: {}", e.toString());
            }
            if (changed) index.refresh();
        }
    }

    // ------------------------------------------------------------------ query side

    @Override
    public Set<String> articlesFor(ResolvedInstrument instrument) {
        Map<String, TagVerdict> verdicts = judgments(instrument);
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, TagVerdict> e : verdicts.entrySet()) {
            if (e.getValue().role() == TagVerdict.Role.SUBJECT) out.add(e.getKey());
        }
        return out;
    }

    /** Cap on the documents handed to the identity desk's corpus veto per name. */
    private static final int EVIDENCE_DOC_CAP = 40;

    /**
     * Basin evidence for the identity desk's CORPUS VETO — the tagging machinery
     * turned 90°: not "is this article about instrument X" but "which documents
     * name this subject at all", answered as their token sets so the desk can test
     * a candidate's extra identity claims for co-occurrence. Multi-word names use
     * the phrase query (in order, slop-tolerant), single words the plural-tolerant
     * token query. Sub-millisecond against the in-RAM index; empty when the basin
     * has not written about the name (absence of coverage is never evidence).
     */
    public List<Set<String>> docsNaming(String name) {
        drain();
        List<String> tokens = TagText.tokens(name);
        Set<String> ids;
        if (tokens.size() >= 2) {
            ids = index.phraseDocs(tokens, PHRASE_SLOP);
        } else if (tokens.size() == 1 && tokens.get(0).length() >= 3) {
            ids = index.tokenDocs(tokens.get(0));
        } else {
            return List.of();
        }
        List<Set<String>> out = new ArrayList<>();
        for (String id : ids) {
            DocRecord d = docs.get(id);
            if (d == null) continue;
            out.add(d.tokenSet());
            if (out.size() >= EVIDENCE_DOC_CAP) break;
        }
        return out;
    }

    /**
     * Corpus entity-DF of one analyzed name token (how many universe instruments
     * carry it) — the desk's genericity axis for the corpus veto: a token hundreds
     * of instruments share ("Group", "Systems") is corporate filler, never a
     * distinctive identity claim. 0 when the universe is unknown.
     */
    public int nameTokenEntityDf(String token) {
        UniverseStats u = universe();
        return !u.known() || token == null ? 0 : u.entityDf(token.toLowerCase(Locale.ROOT));
    }

    /**
     * The identities judged MENTIONED (named beside another subject — attribution
     * lines, teaser mentions) for this instrument: the weaker-context tier that
     * {@link #articlesFor} deliberately excludes. Exposed for measurement and for
     * a possible secondary brief block; same cost as {@link #articlesFor}.
     */
    public Set<String> mentionsFor(ResolvedInstrument instrument) {
        Map<String, TagVerdict> verdicts = judgments(instrument);
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, TagVerdict> e : verdicts.entrySet()) {
            if (e.getValue().role() == TagVerdict.Role.MENTIONED) out.add(e.getKey());
        }
        return out;
    }

    /**
     * The full judgment map for one instrument (identity → verdict), including
     * the rejected and the pending — the explainability window the old word
     * guess never had. Recomputed deterministically from the standing
     * statistics; the heavy statistics themselves are cached.
     */
    public Map<String, TagVerdict> judgments(ResolvedInstrument instrument) {
        drain();
        Handle h = handleFor(instrument);
        if (h == null) return Map.of();
        return judge(h);
    }

    // ------------------------------------------------------------------ handle

    /**
     * One instrument's judging identity: canonical key, hard keys, the name
     * evidence (phrase + lone tokens) and the learned alias spellings.
     */
    private record Handle(String canonKey, String isin, String base,
            List<String> phraseTokens, Map<String, Boolean> loneTokens,
            List<List<String>> aliasPhrases, Set<String> ownTokens, String display) {
    }

    private Handle handleFor(ResolvedInstrument instrument) {
        if (instrument == null) return null;
        UniverseStats u = universe();
        final String queryIsin = instrument.isin().map(i -> i.value()).orElse("");
        final String queryBase = instrument.ticker().map(t -> t.baseSymbol()).orElse("");
        final String queryName = instrument.name() == null ? "" : instrument.name().trim();
        String isin = queryIsin;
        String base = queryBase;
        String name = queryName;

        Optional<InstrumentEntry> entry = u.findByIsin(queryIsin)
                .or(() -> u.findBySymbol(queryBase))
                .or(() -> u.findByName(queryName));
        if (entry.isPresent()) {
            InstrumentEntry e = entry.get();
            if (isin.isEmpty() && e.isin() != null && !e.isin().isBlank()) isin = e.isin().trim().toUpperCase(Locale.ROOT);
            if (base.isEmpty() && e.symbol() != null && !e.symbol().isBlank()) base = UniverseStats.baseOf(e.symbol());
            if (name.isEmpty()) name = e.name();
        }

        List<String> nameTokens = TagText.tokens(name);
        List<String> rawKeys = new ArrayList<>();
        if (!isin.isEmpty()) rawKeys.add("isin:" + isin);
        if (!base.isEmpty()) rawKeys.add("sym:" + base);
        if (!nameTokens.isEmpty()) rawKeys.add("name:" + String.join(" ", nameTokens));
        if (rawKeys.isEmpty()) return null;

        String canon = null;
        for (String raw : rawKeys) {
            String known = keyAlias.get(raw);
            if (known != null) {
                canon = known;
                break;
            }
        }
        if (canon == null) canon = rawKeys.get(0);
        for (String raw : rawKeys) {
            keyAlias.putIfAbsent(raw, canon);
        }

        // Learned spellings for this symbol from the alias ledger — the memory
        // that bridges "Deutsche Telekom AG" and the room's "Telekom".
        List<String> spellings = base.isEmpty()
                ? List.of()
                : aliasSpellings().getOrDefault(base, List.of());

        Set<String> own = new HashSet<>(nameTokens);
        List<List<String>> aliasPhrases = new ArrayList<>();
        List<String> aliasLones = new ArrayList<>();
        for (String spelling : spellings) {
            List<String> ts = TagText.tokens(spelling);
            own.addAll(ts);
            if (ts.size() >= 2) aliasPhrases.add(ts);
            else if (ts.size() == 1) aliasLones.add(ts.get(0));
        }

        List<String> phrase = phraseOf(nameTokens, u);
        Map<String, Boolean> lone = new LinkedHashMap<>();
        if (nameTokens.size() == 1) {
            String t = nameTokens.get(0);
            // The whole name IS this token — always evidence, but a token many
            // instruments share (or an unknown universe) forces the sense path.
            // Below 3 characters a token carries no signal at all ("BE" matched
            // every English "be", 2026-08-13) — same floor the multi-token branch
            // and the alias lones already apply.
            if (t.length() >= 3) {
                lone.put(t, !u.known() || u.entityDf(t) > IDENTIFYING_ENTITY_DF_MAX);
            }
        } else {
            for (String t : nameTokens) {
                if (t.length() < 3) continue;
                if (u.known() && u.entityDf(t) <= IDENTIFYING_ENTITY_DF_MAX) {
                    lone.put(t, false);
                }
            }
        }
        for (String t : aliasLones) {
            if (t.length() < 3) continue;
            lone.putIfAbsent(t, !u.known() || u.entityDf(t) > IDENTIFYING_ENTITY_DF_MAX);
        }

        String display = name.isEmpty() ? (base.isEmpty() ? isin : base) : name;
        return new Handle(canon, isin, base, phrase, lone, aliasPhrases, own, display);
    }

    /**
     * The name's phrase form: generic edge tokens (entity-DF says hundreds of
     * instruments carry them — legal forms, "Technology", "Bank") are shaved
     * off both ends as long as at least two tokens remain. "The Trade Desk,
     * Inc." → "trade desk"; "Super Micro Computer" → "super micro" (the
     * measured recall killer of the strict all-words rule).
     */
    private static List<String> phraseOf(List<String> nameTokens, UniverseStats u) {
        if (nameTokens.size() < 2) return List.of();
        int from = 0;
        int to = nameTokens.size();
        if (u.known()) {
            while (to - from > 2 && u.entityDf(nameTokens.get(to - 1)) >= GENERIC_ENTITY_DF) to--;
            while (to - from > 2 && u.entityDf(nameTokens.get(from)) >= GENERIC_ENTITY_DF) from++;
        }
        return List.copyOf(nameTokens.subList(from, to));
    }

    // ------------------------------------------------------------------ judging

    private Map<String, TagVerdict> judge(Handle h) {
        int total = index.totalDocs();

        // Stage 1 — hard keys.
        Map<String, TagVerdict> out = new HashMap<>();
        for (String key : new String[] {h.isin().isEmpty() ? null : "isin:" + h.isin(),
                h.base().isEmpty() ? null : "sym:" + h.base()}) {
            if (key == null) continue;
            Set<String> ids = hardMentions.get(key);
            if (ids == null) continue;
            for (String id : ids) {
                if (docs.containsKey(id)) {
                    out.put(id, new TagVerdict(TagVerdict.Role.SUBJECT, 0.95, "HARD_KEY"));
                }
            }
        }

        // Stage 2 — candidate retrieval over the index.
        Set<String> phraseIds = new LinkedHashSet<>();
        if (h.phraseTokens().size() >= 2) {
            phraseIds.addAll(index.phraseDocs(h.phraseTokens(), PHRASE_SLOP));
            // The press clips long names to their identifying head ("Super
            // Micro" for Super Micro Computer — the measured 43%-recall lesson
            // of the strict all-words rule): the leading bigram matches too.
            if (h.phraseTokens().size() > 2) {
                phraseIds.addAll(index.phraseDocs(h.phraseTokens().subList(0, 2), PHRASE_SLOP));
            }
        }
        for (List<String> alias : h.aliasPhrases()) {
            phraseIds.addAll(index.phraseDocs(alias, PHRASE_SLOP));
        }
        Map<String, Set<String>> loneIds = new LinkedHashMap<>();
        for (String t : h.loneTokens().keySet()) {
            Set<String> ids = index.tokenDocs(t);
            if (!ids.isEmpty()) loneIds.put(t, ids);
        }

        // The self-supervised profile: seeded by what is UNAMBIGUOUSLY this
        // instrument (hard keys + full phrase), it judges the ambiguous rest.
        List<DocRecord> seeds = new ArrayList<>();
        Set<String> seedIds = new LinkedHashSet<>(out.keySet());
        seedIds.addAll(phraseIds);
        for (String id : seedIds) {
            DocRecord d = docs.get(id);
            if (d != null) seeds.add(d);
        }
        ContextProfile profile = ContextProfile.build(seeds, h.ownTokens(), total, index::docFreq);

        // Evidence pass: which docs carry which name evidence.
        record Evidence(DocRecord doc, String via, double confidence) {
        }
        Map<String, Evidence> evidence = new LinkedHashMap<>();
        for (String id : phraseIds) {
            DocRecord d = docs.get(id);
            if (d == null || out.containsKey(id)) continue;
            evidence.put(id, new Evidence(d, "NAME_PHRASE", 0.85));
        }
        for (Map.Entry<String, Set<String>> e : loneIds.entrySet()) {
            String token = e.getKey();
            boolean forcedCheck = h.loneTokens().get(token);
            int df = index.docFreq(token);
            double basinRatio = total == 0 ? 0.0 : (double) df / total;
            // A ratio needs mass to mean anything: one hit among nine articles
            // is 11% and still just one hit — the flood gate asks for both.
            boolean flood = df >= 5 && basinRatio > LONE_TOKEN_BASIN_RATIO_MAX;
            SenseStats.Sense sense = senseFor(token, e.getValue(), total);
            boolean foreignCompound = sense.boundRatio() >= COMPOUND_BOUND_RATIO
                    && disjoint(sense.boundPartners(), h.ownTokens());
            // Deliberately NOT in this list: multi-cluster structure alone. A
            // proper name with diverse coverage (Nvidia: financing, China,
            // earnings) clusters too — cluster structure DECIDES within
            // already-suspicious tokens, it never convicts an identifying one
            // (measured: it cost half of Nvidia's recall while every true
            // ambiguity case is already caught by one of these gates).
            boolean ambiguous = forcedCheck || flood || foreignCompound
                    || sense.commonWordUsage();
            for (String id : e.getValue()) {
                if (out.containsKey(id) || evidence.containsKey(id)) continue;
                DocRecord d = docs.get(id);
                if (d == null || !DocRecord.containsToken(d.textTokens, token)) continue;
                // Compound part in THIS document ("Sofi-Brille") is no mention.
                if (d.onlyCompoundBound(token, h.ownTokens())) {
                    out.put(id, TagVerdict.none("COMPOUND_PART"));
                    continue;
                }
                // Below the case-statistic's sample floor the DOCUMENT answers
                // the proper-name question itself.
                if (sense.occurrences() < 5 && !d.capitalizedSomewhere(token)) {
                    out.put(id, TagVerdict.none("LOWERCASE_USAGE"));
                    continue;
                }
                if (!ambiguous) {
                    evidence.put(id, new Evidence(d, "NAME_TOKEN", 0.7));
                    continue;
                }
                // Sense work: profile first, then the arbitrated cluster verdict.
                if (profile.usable()) {
                    double cos = profile.cosine(d);
                    if (cos >= ContextProfile.CONFIRM) {
                        evidence.put(id, new Evidence(d, "PROFILE", 0.65));
                        continue;
                    }
                    if (cos <= ContextProfile.REJECT) {
                        out.put(id, TagVerdict.none("PROFILE_MISMATCH"));
                        continue;
                    }
                }
                Set<String> cluster = assignCluster(d, sense);
                if (cluster == null) {
                    // No sense structure to lean on: a genuinely identifying
                    // token stays evidence, an unproven one stays out.
                    if (!forcedCheck && !flood && !foreignCompound && !sense.commonWordUsage()) {
                        evidence.put(id, new Evidence(d, "NAME_TOKEN", 0.6));
                    } else {
                        out.put(id, TagVerdict.none("SENSE_UNPLACED"));
                    }
                    continue;
                }
                String sig = h.canonKey() + '|' + signature(cluster);
                Boolean verdict = senseVerdicts.get(sig);
                if (verdict == null) {
                    // Fuzzy re-use before a fresh model call: the NPMI clusters
                    // shift with every basin drift, so the exact word-set key
                    // thrashed — Gold was asked four times, DICK'S four times,
                    // one word of difference each (2026-08-13). A settled verdict
                    // for the SAME instrument whose cluster substantially overlaps
                    // this one answers it; the answer is cached under the new
                    // signature too, so the family converges instead of growing.
                    verdict = similarVerdict(h.canonKey(), cluster);
                    if (verdict != null) senseVerdicts.put(sig, verdict);
                }
                if (verdict == null && profile.usable() && profile.overlap(cluster) >= 2) {
                    verdict = Boolean.TRUE;
                    senseVerdicts.put(sig, Boolean.TRUE);
                }
                if (verdict == null) {
                    queueSense(sig, h, cluster, e.getValue());
                    out.put(id, new TagVerdict(TagVerdict.Role.PENDING, 0.0, "SENSE_PENDING"));
                } else if (verdict) {
                    evidence.put(id, new Evidence(d, "SENSE", 0.6));
                } else {
                    out.put(id, TagVerdict.none("SENSE_FOREIGN"));
                }
            }
        }

        // Salience pass — learned attribution demotion needs the full picture first.
        int titleHits = 0;
        int multiEntityTitleHits = 0;
        Map<String, Boolean> inTitleByDoc = new HashMap<>();
        Map<String, Boolean> titleForeign = new HashMap<>();
        for (Map.Entry<String, Evidence> e : evidence.entrySet()) {
            DocRecord d = e.getValue().doc();
            boolean inTitle = firstTitleIndex(d, h) >= 0;
            boolean foreign = hasForeignIdentifyingToken(d.titleTokens, h);
            inTitleByDoc.put(e.getKey(), inTitle);
            titleForeign.put(e.getKey(), foreign);
            if (inTitle) {
                titleHits++;
                if (foreign) multiEntityTitleHits++;
            }
        }
        boolean attributor = titleHits >= ATTRIBUTOR_MIN_OBS
                && (double) multiEntityTitleHits / titleHits >= ATTRIBUTOR_RATE;

        for (Map.Entry<String, Evidence> e : evidence.entrySet()) {
            Evidence ev = e.getValue();
            boolean inTitle = Boolean.TRUE.equals(inTitleByDoc.get(e.getKey()));
            boolean foreign = Boolean.TRUE.equals(titleForeign.get(e.getKey()));
            // Two demotions, both learned from structure rather than lists:
            // a name whose basin-wide title pattern is attribution ("DB
            // Research belässt X…") is MENTIONED whenever another entity
            // shares its title; and a name that only appears in the TEASER of
            // an article whose title OPENS with another instrument ("Suss
            // MicroTec Aktie: …" with the analysts in the teaser) is MENTIONED
            // too — an RSS title leads with its subject, so only a LEADING
            // foreign entity demotes; a name deep in the title ("…belässt Tui
            // auf Buy") stays what the attribution statistic says.
            boolean foreignLead = !inTitle
                    && hasForeignIdentifyingToken(leading(ev.doc().titleTokens, 4), h);
            boolean demote = (attributor && inTitle && foreign) || foreignLead;
            out.put(e.getKey(), new TagVerdict(
                    demote ? TagVerdict.Role.MENTIONED : TagVerdict.Role.SUBJECT,
                    demote ? 0.5 : ev.confidence(), ev.via()));
        }
        return out;
    }

    private int firstTitleIndex(DocRecord d, Handle h) {
        if (h.phraseTokens().size() >= 2) {
            int i = DocRecord.phraseIndex(d.titleTokens, h.phraseTokens(), PHRASE_SLOP);
            if (i >= 0) return i;
            if (h.phraseTokens().size() > 2) {
                i = DocRecord.phraseIndex(d.titleTokens, h.phraseTokens().subList(0, 2), PHRASE_SLOP);
                if (i >= 0) return i;
            }
        }
        for (List<String> alias : h.aliasPhrases()) {
            int i = DocRecord.phraseIndex(d.titleTokens, alias, PHRASE_SLOP);
            if (i >= 0) return i;
        }
        for (String t : h.loneTokens().keySet()) {
            int i = DocRecord.firstIndex(d.titleTokens, t);
            if (i >= 0) return i;
        }
        return -1;
    }

    /**
     * Whether the title names ANOTHER identifying entity — a token that maps
     * to at most {@link #IDENTIFYING_ENTITY_DF_MAX} instruments in the
     * universe and is foreign to this handle. The raw material of the
     * attribution statistic; no bank list anywhere.
     */
    private boolean hasForeignIdentifyingToken(List<String> titleTokens, Handle h) {
        UniverseStats u = universe();
        if (!u.known()) return false;
        for (String t : titleTokens) {
            if (t.length() < 3) continue;
            String stem = t.endsWith("s") ? t.substring(0, t.length() - 1) : t;
            if (h.ownTokens().contains(t) || h.ownTokens().contains(stem)) continue;
            int df = u.entityDf(t);
            int dfStem = u.entityDf(stem);
            int best = df > 0 ? df : dfStem;
            if (best >= 1 && best <= IDENTIFYING_ENTITY_DF_MAX) return true;
        }
        return false;
    }

    private static List<String> leading(List<String> tokens, int n) {
        return tokens.size() <= n ? tokens : tokens.subList(0, n);
    }

    private static boolean disjoint(Set<String> a, Set<String> b) {
        for (String s : a) {
            if (b.contains(s)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ sense machinery

    private SenseStats.Sense senseFor(String token, Set<String> docIds, int total) {
        SenseStats.Sense cached = senseCache.get(token);
        if (cached != null && Math.abs(total - cached.atDocCount()) <= SENSE_STALE_DOCS) {
            return cached;
        }
        List<DocRecord> with = new ArrayList<>(docIds.size());
        for (String id : docIds) {
            DocRecord d = docs.get(id);
            if (d != null) with.add(d);
        }
        SenseStats.Sense sense = SenseStats.analyze(token, with, total, index::docFreq);
        senseCache.put(token, sense);
        return sense;
    }

    /** The sense cluster this document belongs to — highest token overlap, or null. */
    private static Set<String> assignCluster(DocRecord d, SenseStats.Sense sense) {
        if (sense.clusters().isEmpty()) return null;
        Set<String> tokens = d.tokenSet();
        Set<String> best = null;
        int bestOverlap = 0;
        for (Set<String> cluster : sense.clusters()) {
            int overlap = 0;
            for (String c : cluster) {
                if (tokens.contains(c)) overlap++;
            }
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = cluster;
            }
        }
        return bestOverlap >= 1 ? best : null;
    }

    private static String signature(Set<String> cluster) {
        return String.join(",", cluster.stream().sorted().limit(8).toList());
    }

    /**
     * The fuzzy verdict lookup behind the exact signature cache: a settled verdict
     * for the same instrument whose cluster terms substantially overlap this one
     * (containment ≥ {@value #VERDICT_REUSE_CONTAINMENT} of the smaller set). One
     * model answer then covers the whole drifting cluster family instead of one
     * exact word set.
     */
    static final double VERDICT_REUSE_CONTAINMENT = 0.5;

    private Boolean similarVerdict(String canonKey, Set<String> cluster) {
        String prefix = canonKey + '|';
        for (Map.Entry<String, Boolean> e : senseVerdicts.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            String[] terms = e.getKey().substring(prefix.length()).split(",");
            int overlap = 0;
            for (String t : terms) {
                if (cluster.contains(t)) overlap++;
            }
            int smaller = Math.min(terms.length, cluster.size());
            if (smaller > 0 && (double) overlap / smaller >= VERDICT_REUSE_CONTAINMENT) {
                return e.getValue();
            }
        }
        return null;
    }

    /** Minimum letters an instrument's display name needs before the model is asked at all. */
    private static final int ARBITER_MIN_NAME_LETTERS = 3;

    /** Queues ONE arbiter question per (instrument, sense cluster) — ever. */
    private void queueSense(String sig, Handle h, Set<String> cluster, Set<String> sampleIds) {
        TagArbiter a = arbiter;
        if (a == null) return;
        // Degenerate handles never reach the model (2026-08-13): a two-character
        // display ("BE (BE)" — the symbol standing in for a missing name) gives the
        // arbiter nothing to judge; every answer would be a coin flip billed as a
        // verdict. Deterministic outcome stands.
        if (h.display().chars().filter(Character::isLetter).count() < ARBITER_MIN_NAME_LETTERS) {
            return;
        }
        if (!senseQueued.add(sig)) return;
        List<String> titles = new ArrayList<>();
        for (String id : sampleIds) {
            DocRecord d = docs.get(id);
            if (d == null) continue;
            Set<String> tokens = d.tokenSet();
            long overlap = cluster.stream().filter(tokens::contains).count();
            if (overlap >= 1 && titles.size() < 4) {
                titles.add(String.join(" ", d.titleTokens));
            }
        }
        // Without a single concrete headline the arbiter would judge bare cluster
        // words — exactly the blind spot that approved generic quarter vocabulary
        // as ABOUT (2026-08-13). No evidence, no question; a later query retries
        // once titles exist.
        if (titles.isEmpty()) {
            senseQueued.remove(sig);
            return;
        }
        List<String> terms = cluster.stream().sorted().limit(10).toList();
        String line = h.display() + (h.base().isEmpty() ? "" : " (" + h.base() + ")");
        try {
            arbiterWorker.submit(() -> {
                try {
                    Optional<Boolean> verdict = a.senseAboutInstrument(line, terms, titles);
                    if (verdict.isPresent()) {
                        senseVerdicts.put(sig, verdict.get());
                        LOG.info("[TAG] sense verdict for {} × [{}]: {}", line,
                                String.join(",", terms), verdict.get() ? "ABOUT" : "FOREIGN");
                    } else {
                        senseQueued.remove(sig); // abstained — a later query may retry
                    }
                } catch (Exception e) {
                    senseQueued.remove(sig);
                    LOG.debug("[TAG] sense arbitration failed (retryable): {}", e.toString());
                }
            });
        } catch (RejectedExecutionException closed) {
            senseQueued.remove(sig);
        }
    }

    // ------------------------------------------------------------------ shared state

    private UniverseStats universe() {
        List<InstrumentEntry> entries;
        try {
            entries = universeSource == null ? List.of() : universeSource.get();
        } catch (Exception e) {
            entries = List.of();
        }
        int size = entries == null ? 0 : entries.size();
        if (size != universeSeenSize) {
            synchronized (this) {
                if (size != universeSeenSize) {
                    universe = UniverseStats.over(entries);
                    universeSeenSize = size;
                    if (size > 0) LOG.info("[TAG] universe statistics over {} instruments", size);
                }
            }
        }
        return universe;
    }

    private Map<String, List<String>> aliasSpellings() {
        if (aliases == null) return Map.of();
        int version = aliases.version();
        if (version != aliasSeenVersion) {
            synchronized (this) {
                if (version != aliasSeenVersion) {
                    Map<String, List<String>> reversed = new HashMap<>();
                    for (Map.Entry<String, String> e : aliases.all().entrySet()) {
                        String spelling = e.getKey();
                        String base = UniverseStats.baseOf(e.getValue());
                        // Only TRUSTED readings ride: one posting is a data
                        // point, not a memory (the store's own doctrine).
                        List<AliasCandidate> readings = aliases.candidatesFor(spelling);
                        if (readings.isEmpty() || !readings.get(0).isTrusted()) continue;
                        reversed.computeIfAbsent(base, k -> new ArrayList<>())
                                .add(NameKey.normalize(spelling));
                    }
                    aliasSpellings = reversed;
                    aliasSeenVersion = version;
                }
            }
        }
        return aliasSpellings;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void close() throws IOException {
        ingestWorker.shutdownNow();
        arbiterWorker.shutdownNow();
        index.close();
    }
}
