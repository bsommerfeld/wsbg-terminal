package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.db.MentionArchive;
import de.bsommerfeld.wsbg.terminal.db.MentionDay;
import de.bsommerfeld.wsbg.terminal.db.RedditIngestListener;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.instruments.AliasStore;
import de.bsommerfeld.wsbg.terminal.instruments.InstrumentCorpus;
import de.bsommerfeld.wsbg.terminal.instruments.MentionLexicon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The cage's own ticker counter: every post and comment r/wallstreetbetsGER
 * produces, scanned for instrument mentions and booked on the day it was
 * WRITTEN.
 *
 * <p><b>Fully mechanical.</b> No model, no request, no judgement call - the
 * {@link MentionLexicon} decides, and it decides on the corpus and the learned
 * alias memory alone. That is what makes the number trustworthy: it counts what
 * the room wrote, it does not interpret it.
 *
 * <p><b>Nothing is de-duplicated.</b> Twenty {@code $GME} in one comment are
 * twenty mentions - if it gets spammed, it gets spammed, and the reader can see
 * that for themselves. The only thing guarded against is counting the same
 * comment twice because a re-scrape or a restart handed it over again, which
 * the archive settles by Reddit id.
 *
 * <p><b>Write the spelling, resolve on read.</b> The archive stores what the
 * room wrote; {@link #rows} maps it onto symbols at query time. So a short form
 * the resolver only settles next week folds into its ticker throughout the
 * history already on disk - the count of the past gets better, it never gets
 * rewritten.
 */
@Singleton
public final class MentionCounter implements RedditIngestListener {

    private static final Logger LOG = LoggerFactory.getLogger(MentionCounter.class);

    private final MentionArchive archive;
    private final AliasStore aliases;

    /** Null in tests/harnesses - without ground truth the counter recognises nothing. */
    private volatile InstrumentCorpus corpus;

    private volatile MentionLexicon lexicon = MentionLexicon.empty();
    private volatile int lexiconEntries = -1;
    private volatile int lexiconAliases = -1;

    @Inject
    public MentionCounter(RedditRepository repository, MentionArchive archive, AliasStore aliases,
            InstrumentCorpus corpus) {
        this.archive = archive;
        this.aliases = aliases;
        this.corpus = corpus;
        repository.setIngestListener(this);
        LOG.info("[MENTIONS] counter armed on the room stream.");
    }

    /** Test/harness constructor - no repository to hook, corpus supplied directly. */
    MentionCounter(MentionArchive archive, AliasStore aliases, InstrumentCorpus corpus) {
        this.archive = archive;
        this.aliases = aliases;
        this.corpus = corpus;
    }

    // -- ingest --

    @Override
    public void onThread(RedditThread thread) {
        if (thread == null) return;
        StringBuilder text = new StringBuilder();
        if (thread.title() != null) text.append(thread.title()).append('\n');
        if (thread.textContent() != null) text.append(thread.textContent());
        count(thread.id(), thread.createdUtc(), text.toString());
    }

    @Override
    public void onComment(RedditComment comment) {
        if (comment == null) return;
        count(comment.id(), comment.createdUtc(), comment.body());
    }

    /**
     * Scans one item and books it on the day it was written - not the day we
     * happened to scrape it, so a re-scrape lands on the same day it was
     * already counted on and is dropped there.
     */
    private void count(String id, long createdUtc, String text) {
        if (id == null || id.isBlank() || text == null || text.isBlank()) return;
        Map<String, Integer> phrases = tally(text);
        if (phrases.isEmpty()) return;
        Instant when = createdUtc > 0 ? Instant.ofEpochSecond(createdUtc) : Instant.now();
        archive.record(when, id, phrases);
    }

    /** One item's mentions: spelling → how often it was written. */
    Map<String, Integer> tally(String text) {
        Map<String, Integer> counts = new HashMap<>();
        for (String phrase : lexicon().scan(text)) counts.merge(phrase, 1, Integer::sum);
        return counts;
    }

    // -- read --

    /**
     * One row of the counter: an instrument (or, until the resolver has settled
     * the spelling, the spelling itself) and how often the room wrote it.
     *
     * @param symbol   the ticker, or null while this is only a recognised spelling
     * @param label    what to show - the registered name, else the spelling as written
     * @param mentions how often it was written in the window
     * @param resolved whether this row stands on a verified instrument
     */
    public record MentionRow(String symbol, String label, int mentions, boolean resolved) {}

    /** The day's count, resolved and ranked - the widget's default view. */
    public List<MentionRow> today() {
        LocalDate today = LocalDate.now(archive.zone());
        return rows(today, today);
    }

    /**
     * Everything the room mentioned in {@code [from, to]}, folded into one
     * ranked view: verified instruments first, then the spellings still waiting
     * for a verdict, each ordered by how often it was written.
     */
    public List<MentionRow> rows(LocalDate from, LocalDate to) {
        return resolve(archive.fold(from, to));
    }

    /** Day by day across the window - the slider's timeline, oldest first. */
    public List<MentionDay> days(LocalDate from, LocalDate to) {
        return archive.range(from, to);
    }

    /** The oldest day the counter can show; empty before the first mention was booked. */
    public Optional<LocalDate> earliestDay() {
        return archive.earliestDay();
    }

    /** The day boundary the count is bucketed in - the page must ask in the same one. */
    public ZoneId zone() {
        return archive.zone();
    }

    /** Folds stored spellings onto instruments with the lexicon as it stands TODAY. */
    List<MentionRow> resolve(MentionDay counted) {
        MentionLexicon lex = lexicon();
        Map<String, MentionRow> merged = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : counted.phrases().entrySet()) {
            String phrase = e.getKey();
            int n = e.getValue();
            String symbol = lex.symbolFor(phrase).orElse(null);
            String key = symbol != null ? symbol : "?" + phrase;
            String label = symbol != null ? lex.nameFor(symbol).orElse(symbol) : phrase;
            MentionRow existing = merged.get(key);
            merged.put(key, existing == null
                    ? new MentionRow(symbol, label, n, symbol != null)
                    : new MentionRow(symbol, label, existing.mentions() + n, symbol != null));
        }
        List<MentionRow> out = new ArrayList<>(merged.values());
        out.sort(Comparator.comparing(MentionRow::resolved).reversed()
                .thenComparing(Comparator.comparingInt(MentionRow::mentions).reversed())
                .thenComparing(MentionRow::label));
        return out;
    }

    // -- lexicon --

    /** Installs the ground truth after construction (the harness path). */
    void setInstrumentCorpus(InstrumentCorpus corpus) {
        this.corpus = corpus;
    }

    /**
     * The current lexicon, rebuilt whenever the corpus refreshed or the alias
     * memory learned something - the two events that change what counts.
     *
     * <p>This sits in the scrapers' ingest path, so the common case (nothing
     * changed) must not take a lock: two volatile reads decide it, and only an
     * actual rebuild serialises.
     */
    private MentionLexicon lexicon() {
        int entries = corpus == null ? 0 : corpus.size();
        int learned = aliases == null ? 0 : aliases.version();
        if (entries == lexiconEntries && learned == lexiconAliases) return lexicon;
        return rebuild(entries, learned);
    }

    private synchronized MentionLexicon rebuild(int entries, int learned) {
        if (entries == lexiconEntries && learned == lexiconAliases) return lexicon;
        MentionLexicon built = MentionLexicon.build(
                corpus == null ? List.of() : corpus.entries(),
                aliases == null ? Map.of() : aliases.all());
        lexicon = built;
        lexiconEntries = entries;
        lexiconAliases = learned;
        LOG.info("[MENTIONS] lexicon rebuilt: {} spelling(s) over {} instrument(s)",
                built.size(), entries);
        return built;
    }
}
