package de.bsommerfeld.wsbg.terminal.instruments;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns free-form room text into instrument mentions — <b>mechanically</b>, with
 * no model in the loop and no network call per mention.
 *
 * <p>Three ways a mention is recognised, longest match first:
 * <ol>
 *   <li><b>Dollar shape</b> — {@code $XYZ}, the room's unambiguous ticker
 *       notation. Resolved against the corpus symbols; an unknown shape is
 *       still counted, just unresolved (see below).</li>
 *   <li><b>Registered name</b> — a corpus name or a {@link AliasStore learned}
 *       spelling, matched as a whole word sequence.</li>
 *   <li><b>Distinctive word</b> — a single word that occurs in the name of at
 *       most {@link #DISTINCTIVE_MAX_OWNERS} instruments in the whole corpus.
 *       Rarity is the only filter, measured on the corpus itself: a word like
 *       "deutsche" carries hundreds of owners and is ignored, a word like
 *       "telekom" carries a handful and is kept. No stop list, no legal-form
 *       stripping — those would be curation.</li>
 * </ol>
 *
 * <p><b>Recognised is not the same as resolved.</b> A hit from rule 3 (and an
 * unknown dollar shape) is counted under the SPELLING, not under a symbol,
 * because nothing here has the right to decide which instrument it means. It
 * folds into the symbol the day the resolver settles that spelling and the
 * alias memory learns it — which is why callers must store the phrase and call
 * {@link #symbolFor} at read time, never at write time.
 */
public final class MentionLexicon {

    /** Longest name the phrase matcher will try (corpus names beyond that are ignored). */
    static final int MAX_PHRASE_TOKENS = 6;
    /** A word occurring in more corpus names than this is generic, not distinctive. */
    static final int DISTINCTIVE_MAX_OWNERS = 3;
    /** Shortest single-word registered name that may match on its own. */
    static final int MIN_NAME_LENGTH = 3;
    /** Shortest distinctive word that may be counted as a candidate spelling. */
    static final int MIN_CANDIDATE_LENGTH = 4;

    /** The room's ticker notation — the same shape {@code TickerExtractor} accepts. */
    private static final Pattern DOLLAR_TICKER =
            Pattern.compile("\\$([A-Za-z]{1,5}(?:[.-][A-Za-z]{1,3})?)\\b");

    private final Map<String, String> phraseToSymbol;
    private final Map<String, String> symbolToName;
    private final Set<String> symbols;
    private final Set<String> phraseHeads;
    private final Map<String, Integer> wordOwners;

    private MentionLexicon(Map<String, String> phraseToSymbol, Map<String, String> symbolToName,
            Set<String> symbols, Set<String> phraseHeads, Map<String, Integer> wordOwners) {
        this.phraseToSymbol = phraseToSymbol;
        this.symbolToName = symbolToName;
        this.symbols = symbols;
        this.phraseHeads = phraseHeads;
        this.wordOwners = wordOwners;
    }

    /** An empty lexicon — recognises nothing (the state before the corpus has loaded). */
    public static MentionLexicon empty() {
        return new MentionLexicon(Map.of(), Map.of(), Set.of(), Set.of(), Map.of());
    }

    /**
     * Builds the lexicon over a corpus snapshot plus the learned spellings.
     * When two instruments carry the same normalized name the FIRST wins — the
     * corpus is already ordered by source priority, so this is the same
     * tie-break its dedup-merge uses. Learned aliases override both.
     */
    public static MentionLexicon build(List<InstrumentEntry> entries, Map<String, String> aliases) {
        Map<String, String> phrases = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        Set<String> syms = new HashSet<>();
        Map<String, Integer> owners = new HashMap<>();

        if (entries != null) {
            for (InstrumentEntry e : entries) {
                if (e == null || e.symbol() == null || e.symbol().isBlank()) continue;
                String symbol = e.symbol().trim().toUpperCase(Locale.ROOT);
                syms.add(symbol);
                names.putIfAbsent(symbol, e.name());

                List<String> tokens = NameKey.tokens(e.name());
                if (tokens.isEmpty()) continue;
                Set<String> distinct = new HashSet<>(tokens);
                for (String t : distinct) owners.merge(t, 1, Integer::sum);

                if (tokens.size() > MAX_PHRASE_TOKENS) continue;
                if (tokens.size() == 1 && tokens.get(0).length() < MIN_NAME_LENGTH) continue;
                phrases.putIfAbsent(String.join(" ", tokens), symbol);
            }
        }
        if (aliases != null) {
            for (Map.Entry<String, String> a : aliases.entrySet()) {
                String key = NameKey.normalize(a.getKey());
                if (key.isEmpty() || a.getValue() == null || a.getValue().isBlank()) continue;
                if (key.split(" ").length > MAX_PHRASE_TOKENS) continue;
                phrases.put(key, a.getValue().trim().toUpperCase(Locale.ROOT));
            }
        }
        Set<String> heads = new HashSet<>();
        for (String p : phrases.keySet()) {
            int space = p.indexOf(' ');
            heads.add(space < 0 ? p : p.substring(0, space));
        }
        return new MentionLexicon(Map.copyOf(phrases), Map.copyOf(names), Set.copyOf(syms),
                Set.copyOf(heads), Map.copyOf(owners));
    }

    /** How many spellings map to a symbol (corpus names + learned aliases). */
    public int size() {
        return phraseToSymbol.size();
    }

    /**
     * Every mention in {@code text}, as phrase keys, <b>one entry per
     * occurrence</b> — repetition is the signal, so nothing is deduplicated
     * here. A dollar shape keeps its {@code $}; every other hit is the
     * normalized spelling.
     */
    public List<String> scan(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;

        boolean[] masked = new boolean[text.length()];
        Matcher m = DOLLAR_TICKER.matcher(text);
        while (m.find()) {
            out.add("$" + m.group(1).toLowerCase(Locale.ROOT));
            for (int i = m.start(); i < m.end(); i++) masked[i] = true;
        }

        List<Word> words = words(text, masked);
        int i = 0;
        while (i < words.size()) {
            int consumed = matchPhrase(words, i, out);
            if (consumed > 0) {
                i += consumed;
                continue;
            }
            Word w = words.get(i);
            if (isCandidate(w)) out.add(w.norm());
            i++;
        }
        return out;
    }

    /**
     * The symbol this phrase stands for, or empty while it is only a
     * recognised spelling. Called at READ time so a spelling learned later
     * folds into its symbol across the whole stored history.
     */
    public Optional<String> symbolFor(String phrase) {
        if (phrase == null || phrase.isBlank()) return Optional.empty();
        if (phrase.startsWith("$")) {
            String sym = phrase.substring(1).toUpperCase(Locale.ROOT);
            if (symbols.contains(sym)) return Optional.of(sym);
            return Optional.ofNullable(phraseToSymbol.get(NameKey.normalize(phrase)));
        }
        return Optional.ofNullable(phraseToSymbol.get(NameKey.normalize(phrase)));
    }

    /** The registered name behind a symbol, when the corpus carries it. */
    public Optional<String> nameFor(String symbol) {
        if (symbol == null || symbol.isBlank()) return Optional.empty();
        return Optional.ofNullable(symbolToName.get(symbol.trim().toUpperCase(Locale.ROOT)));
    }

    // -- matching --

    /**
     * Longest registered name starting at {@code from}; appends the hit and
     * returns how many words it swallowed (0 when nothing matched). A
     * single-word name must be written capitalised — in German prose an
     * uncapitalised word is not a company.
     */
    private int matchPhrase(List<Word> words, int from, List<String> out) {
        if (!phraseHeads.contains(words.get(from).norm())) return 0;
        int max = Math.min(MAX_PHRASE_TOKENS, words.size() - from);
        for (int len = max; len >= 1; len--) {
            StringBuilder b = new StringBuilder();
            for (int k = 0; k < len; k++) {
                if (k > 0) b.append(' ');
                b.append(words.get(from + k).norm());
            }
            String symbol = phraseToSymbol.get(b.toString());
            if (symbol == null) continue;
            if (len == 1 && !words.get(from).capitalized()) continue;
            out.add(b.toString());
            return len;
        }
        return 0;
    }

    /** A capitalised word rare enough in the corpus to be worth counting as a spelling. */
    private boolean isCandidate(Word w) {
        if (!w.capitalized() || w.norm().length() < MIN_CANDIDATE_LENGTH) return false;
        Integer count = wordOwners.get(w.norm());
        return count != null && count <= DISTINCTIVE_MAX_OWNERS;
    }

    // -- tokenization --

    /** One word of the text: its lookup form plus whether it was written capitalised. */
    private record Word(String norm, boolean capitalized) {}

    private static List<Word> words(String text, boolean[] masked) {
        List<Word> out = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            if (masked[i] || !Character.isLetterOrDigit(text.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            while (i < text.length() && !masked[i] && Character.isLetterOrDigit(text.charAt(i))) i++;
            String raw = text.substring(start, i);
            String norm = NameKey.normalize(raw);
            if (!norm.isEmpty()) out.add(new Word(norm, Character.isUpperCase(raw.charAt(0))));
        }
        return out;
    }
}
