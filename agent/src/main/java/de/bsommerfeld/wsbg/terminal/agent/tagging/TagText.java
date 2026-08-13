package de.bsommerfeld.wsbg.terminal.agent.tagging;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.charfilter.MappingCharFilter;
import org.apache.lucene.analysis.charfilter.NormalizeCharMap;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import java.io.Reader;
import java.io.StringReader;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The ONE text normalisation of the tagging engine: German umlauts →
 * ae/oe/ue/ss transliteration BEFORE any diacritic strip (the hard-won lesson
 * from {@code NameMatching.tokenize} — bare NFD turns "Münchener" into
 * "munchener" and misses the expanded spelling), then Lucene's standard
 * tokenizer, lower-casing and ASCII folding for the remaining accents.
 *
 * <p><b>Deliberately NO stop filter and NO curated word list of any kind</b> —
 * per the house rule, what a stop list would do is done statistically: the
 * entity-DF over the instrument universe ({@link UniverseStats}) and the
 * basin's own document frequencies decide which tokens carry meaning.
 */
public final class TagText {

    private static final NormalizeCharMap UMLAUTS = buildUmlauts();

    private static final Analyzer ANALYZER = new Analyzer() {
        @Override
        protected TokenStreamComponents createComponents(String fieldName) {
            Tokenizer source = new StandardTokenizer();
            TokenStream stream = new LowerCaseFilter(source);
            stream = new ASCIIFoldingFilter(stream);
            return new TokenStreamComponents(source, stream);
        }

        @Override
        protected Reader initReader(String fieldName, Reader reader) {
            return new MappingCharFilter(UMLAUTS, reader);
        }
    };

    private TagText() {
    }

    private static NormalizeCharMap buildUmlauts() {
        NormalizeCharMap.Builder b = new NormalizeCharMap.Builder();
        b.add("ä", "ae");
        b.add("ö", "oe");
        b.add("ü", "ue");
        b.add("ß", "ss");
        b.add("Ä", "ae");
        b.add("Ö", "oe");
        b.add("Ü", "ue");
        b.add("ẞ", "ss");
        return b.build();
    }

    /** The shared analyzer — index side and query side use the SAME one. */
    public static Analyzer analyzer() {
        return ANALYZER;
    }

    /** The analyzed token sequence of {@code s} (order kept, length ≥ 2). */
    public static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;
        try (TokenStream ts = ANALYZER.tokenStream("text", new StringReader(s))) {
            CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                String t = term.toString();
                if (t.length() >= 2) out.add(t);
            }
            ts.end();
        } catch (Exception e) {
            // Analysis of an in-memory string cannot meaningfully fail; if it
            // does, an empty token list degrades to "no evidence", never a crash.
            return List.of();
        }
        return out;
    }

    /**
     * The character-level normalisation ALONE (no tokenizing): lower-case,
     * umlaut transliteration, diacritics stripped — hyphens and word shapes
     * kept, which is what the compound test ("Sofi-Brille") reads.
     */
    public static String normalize(String s) {
        return normalizeKeepCase(s).toLowerCase(Locale.ROOT);
    }

    /**
     * Like {@link #normalize} but CASE-PRESERVING: umlauts transliterated,
     * diacritics stripped, letter case untouched. The case statistics read off
     * this — whether a token lives in the basin as a proper name ("Nvidia")
     * or as a common word ("trade", "gold price") is a listed-nowhere
     * discriminance signal.
     */
    public static String normalizeKeepCase(String s) {
        if (s == null) return "";
        String deUmlaut = s
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue").replace("ẞ", "Ss");
        return Normalizer.normalize(deUmlaut, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }
}
