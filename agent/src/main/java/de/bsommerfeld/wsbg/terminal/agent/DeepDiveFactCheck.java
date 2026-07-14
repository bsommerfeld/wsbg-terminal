package de.bsommerfeld.wsbg.terminal.agent;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The KI-DD's deterministic examiner — the mechanical half of the supervisory
 * review a real research desk runs before anything is published (the FINRA
 * Series-16 raster: figures must reconcile with the material, sources must be
 * the ones delivered, no protocol residue, no torn sentences). It inspects ONE
 * section draft against THAT section's material and returns concrete
 * objections; the model never sees a report the examiner has not passed, and
 * a figure objection that survives revision gets its sentence removed rather
 * than archived (a lost sentence beats a false statement — the fact-checking
 * end state is "every claim sourced or out").
 *
 * <p>Checks, in order of hardness:
 * <ul>
 *   <li><b>Figure fidelity</b> — every number token in the draft must exist in
 *       the material (locale-normalized, small rounding tolerance, unit-scale
 *       tolerant: "36,8 Mrd." matches a material line in thousands). Years,
 *       ordinals and counts ≤ 31 are exempt (dates and "2. Quartal" would
 *       drown the signal). This is what kills the spliced-figure class
 *       (live-observed SAP 2026-07-13: "33,93 P/E" was another year's figure).</li>
 *   <li><b>Date fidelity</b> — dotted and written-out dates must exist in the
 *       material's ISO date set.</li>
 *   <li><b>Marker discipline</b> — only source markers of THIS section's
 *       material may be cited.</li>
 *   <li><b>Protocol residue</b> — {@code <<<}, code fences, headings inside a
 *       body, placeholder/meta vocabulary. (The old edit-script sentinels
 *       reached the ARCHIVE once; this check makes that class impossible.)</li>
 *   <li><b>Sentence integrity</b> — every paragraph ends like a sentence
 *       (torn splices fail here).</li>
 *   <li><b>Repetition</b> — a long sentence may not appear twice.</li>
 * </ul>
 *
 * <p><b>Markdown tables are licensed report content (2026-07-14):</b> a pipe
 * row is TYPESETTING, not a sentence — the shape checks (integrity, repeats,
 * paragraph reflow, source-list scrub) leave table lines alone, while the
 * FIDELITY checks (figures, dates, markers) still scan every cell. Surgery on
 * a table removes the offending ROW, never sentence-joins the block.
 */
final class DeepDiveFactCheck {

    private DeepDiveFactCheck() {
    }

    /** One concrete objection: the offending quote and what is wrong with it. */
    record Objection(String quote, String problem, Kind kind) {
        enum Kind {
            FIGURE, DATE, MARKER, RESIDUE, INTEGRITY, REPEAT, LENGTH, DENSITY,
            NUMBER_WORDS
        }

        /**
         * Figure/date/number-word objections are HARD: unresolved ones cost
         * the sentence. Number words are hard for the same epistemic reason
         * as unverifiable figures — a value spelled out in words is INVISIBLE
         * to the figure check, and the model learned exactly that (live run 8:
         * "vierundsiebzig Komma fünf Prozent" for 47,5 %, a wrong year written
         * out — reward hacking around the examiner). Unverifiable rendering =
         * "belegt oder raus".
         */
        boolean hard() {
            return kind == Kind.FIGURE || kind == Kind.DATE || kind == Kind.NUMBER_WORDS;
        }
    }

    /** Integers at or below this are ordinals/counts, not checkable figures. */
    private static final int SMALL_INT_EXEMPT = 31;
    /** Relative tolerance for a figure match (rounding in prose). */
    private static final double REL_TOLERANCE = 0.006;
    /** Unit scales the prose may apply to a raw material figure (Mrd./Mio./Tsd.). */
    private static final double[] SCALES = {1.0, 1e3, 1e6, 1e9};
    /** Below this normalized length a repeated sentence is legitimate phrasing. */
    private static final int MIN_REPEAT_SENTENCE_CHARS = 60;
    /** A section draft shorter than this carries no substance. */
    static final int MIN_SECTION_CHARS = 180;
    /** A section draft longer than this ignored its length contract. */
    static final int MAX_SECTION_CHARS = 3400;
    /**
     * More non-exempt figures than this in one section is a NUMBER WALL —
     * the charts carry the series, the prose keeps the load-bearing few
     * (user finding 2026-07-13: "viele viele Zahlen").
     */
    static final int MAX_FIGURES_PER_SECTION = 9;

    /** Tokens that may NEVER appear in report prose — protocol/meta residue. */
    private static final String[] FORBIDDEN = {
            "<<<", "```", "{{", "}}",
            "Faktenblatt", "fact sheet", "Materialpaket", "MATERIAL:",
            "STANDING REPORT", "NEW MATERIAL",
            "(Dieser Abschnitt folgt",
    };

    private static final Pattern MARKER = Pattern.compile("\\[(\\d{1,2})]");
    /**
     * Number tokens: space-grouped ("36 800 000") or separator-decimal — a
     * separator counts only when a digit follows DIRECTLY, so two numbers
     * joined by ", " never glue into one token (live-observed smoke run:
     * "132.63, 128.17" read as one unparseable figure and cost a true sentence).
     */
    private static final Pattern NUMBER = Pattern.compile(
            "[+\\-−]?\\d{1,3}(?:[  ]\\d{3})+(?:[.,]\\d+)?|[+\\-−]?\\d+(?:[.,]\\d+)*");
    /** Bracket content that is NOT a plain source marker — prose carries no other brackets. */
    private static final Pattern NON_MARKER_BRACKET = Pattern.compile("\\[(?!\\d{1,2}])[^\\]\\n]+]");
    /** Dotted numeric date (13.07.2026). */
    private static final Pattern DOTTED_DATE = Pattern.compile("\\b(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})\\b");
    /** ISO date in the material (2026-07-24). */
    private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");
    /** Written-out German/English date ("24. Juli 2026", "July 24, 2026", "24 July 2026"). */
    private static final Pattern WORD_DATE = Pattern.compile(
            "\\b(\\d{1,2})\\.?\\s+([A-Za-zäöüÄÖÜ]+),?\\s+(\\d{4})\\b|\\b([A-Za-z]+)\\s+(\\d{1,2}),\\s+(\\d{4})\\b");

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("januar", 1), Map.entry("january", 1),
            Map.entry("februar", 2), Map.entry("february", 2),
            Map.entry("märz", 3), Map.entry("maerz", 3), Map.entry("march", 3),
            Map.entry("april", 4),
            Map.entry("mai", 5), Map.entry("may", 5),
            Map.entry("juni", 6), Map.entry("june", 6),
            Map.entry("juli", 7), Map.entry("july", 7),
            Map.entry("august", 8),
            Map.entry("september", 9),
            Map.entry("oktober", 10), Map.entry("october", 10),
            Map.entry("november", 11),
            Map.entry("dezember", 12), Map.entry("december", 12));

    /**
     * Inspects one section draft against its material. {@code germanText} is the
     * REPORT-LANGUAGE flag: it picks the draft's number locale (German
     * comma-decimal vs ROOT dot-decimal) AND the language of the objection
     * texts — they are user-facing (the desk journal shows them) and steer a
     * revision model writing in that language. The material is ALWAYS
     * ROOT-formatted (our own block renderers).
     */
    static List<Objection> inspect(String draft, String material, Set<Integer> allowedMarkers,
            boolean germanText) {
        return inspect(draft, material, allowedMarkers, germanText, false);
    }

    /**
     * Inspect variant for MIXED-locale material (the Wetterbericht: the shelf
     * carries German-formatted stat blocks AND model-condensed wire prose in
     * the user language) — the allowed figure set is then the UNION of the
     * ROOT and the German parse of the material. Slightly more lenient than
     * the DD's strict ROOT contract, never stricter.
     */
    static List<Objection> inspect(String draft, String material, Set<Integer> allowedMarkers,
            boolean germanText, boolean mixedLocaleMaterial) {
        List<Objection> out = new ArrayList<>();
        if (draft == null || draft.isBlank()) {
            out.add(new Objection("", germanText ? "leerer Entwurf" : "empty draft",
                    Objection.Kind.LENGTH));
            return out;
        }
        String text = draft.strip();
        if (text.length() < MIN_SECTION_CHARS) {
            out.add(new Objection(head(text), germanText
                    ? "Entwurf unter dem Substanzminimum (" + text.length() + " Zeichen)"
                    : "draft below the substance minimum (" + text.length() + " chars)",
                    Objection.Kind.LENGTH));
        }
        if (text.length() > MAX_SECTION_CHARS) {
            out.add(new Objection(head(text), germanText
                    ? "Entwurf missachtet den Längenvertrag (" + text.length() + " Zeichen)"
                    : "draft ignored its length contract (" + text.length() + " chars)",
                    Objection.Kind.LENGTH));
        }
        residueObjections(text, germanText, out);
        markerObjections(text, allowedMarkers, germanText, out);
        integrityObjections(text, germanText, out);
        repeatObjections(text, germanText, out);
        // Paragraph SHAPE is no longer an objection: the weave/revision cycles
        // provably re-flatten the breaks every round (live: the wall-of-text
        // objection fired 10+ times on one section) — the deterministic
        // {@link #splitLongParagraphs} typesets the shape instead.
        figureObjections(text, material, germanText, mixedLocaleMaterial, out);
        if (germanText) numberWordObjections(text, out);
        return out;
    }

    // -- markdown table awareness (tables are licensed report content, 2026-07-14) --

    /**
     * A markdown pipe-table line (header, separator or data row): after trim it
     * opens with '|' and carries at least one more pipe. Table rows legitimately
     * end without sentence punctuation and may carry markers and "·"-joined cell
     * text — the SHAPE checks must leave them alone, while the FIDELITY checks
     * (figures, dates, markers) still see every cell.
     */
    static boolean isTableLine(String line) {
        if (line == null) return false;
        String s = line.strip();
        return s.length() >= 2 && s.charAt(0) == '|' && s.indexOf('|', 1) > 0;
    }

    private static boolean containsTableLine(String block) {
        if (block == null || block.indexOf('|') < 0) return false;
        for (String line : block.split("\n")) {
            if (isTableLine(line)) return true;
        }
        return false;
    }

    /** The text with every table line removed — input for the sentence-shaped checks. */
    private static String withoutTableLines(String text) {
        if (text == null || text.indexOf('|') < 0) return text;
        StringBuilder out = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            if (isTableLine(line)) continue;
            if (out.length() > 0) out.append('\n');
            out.append(line);
        }
        return out.toString();
    }

    // -- paragraph typesetting --

    /** More sentences than this in ONE paragraph is a wall of text. */
    private static final int MAX_SENTENCES_PER_PARAGRAPH = 4;
    /** Target sentences per paragraph when a wall of text is re-flowed. */
    private static final int SPLIT_TARGET_SENTENCES = 3;

    /**
     * Deterministic wall-of-text repair (replaces the retired STRUCTURE
     * objection, which never converged — every weave re-emission flattened the
     * paragraph breaks again, and each round cost two model calls): any
     * paragraph beyond {@value #MAX_SENTENCES_PER_PARAGRAPH} sentences is split
     * at sentence boundaries into successive paragraphs of roughly
     * {@value #SPLIT_TARGET_SENTENCES} sentences — order and every sentence
     * kept verbatim, shaped paragraphs untouched.
     */
    static String splitLongParagraphs(String body) {
        if (body == null || body.isBlank()) return body;
        List<String> outParas = new ArrayList<>();
        for (String rawPara : body.split("\n\\s*\n")) {
            String para = rawPara.strip();
            if (para.isEmpty()) continue;
            // A table block is ATOMIC typesetting: never split inside it, and
            // its rows are no sentences to count.
            if (containsTableLine(para)) {
                outParas.add(para);
                continue;
            }
            List<String> ss = sentences(para);
            if (ss.size() <= MAX_SENTENCES_PER_PARAGRAPH) {
                outParas.add(para);
                continue;
            }
            // Near-equal chunks, each at most the target size.
            int chunks = (ss.size() + SPLIT_TARGET_SENTENCES - 1) / SPLIT_TARGET_SENTENCES;
            int from = 0;
            for (int c = 0; c < chunks; c++) {
                int remaining = ss.size() - from;
                int size = (remaining + (chunks - c) - 1) / (chunks - c);
                StringBuilder chunk = new StringBuilder(256);
                for (int i = from; i < from + size; i++) {
                    if (chunk.length() > 0) chunk.append(' ');
                    chunk.append(ss.get(i).strip());
                }
                outParas.add(chunk.toString());
                from += size;
            }
        }
        return String.join("\n\n", outParas);
    }

    // -- protocol residue --

    private static void residueObjections(String text, boolean de, List<Objection> out) {
        for (String bad : FORBIDDEN) {
            int i = text.indexOf(bad);
            if (i >= 0) {
                out.add(new Objection(around(text, i), de
                        ? "verbotenes Protokoll-/Meta-Token '" + bad + "'"
                        : "forbidden protocol/meta token '" + bad + "'",
                        Objection.Kind.RESIDUE));
            }
        }
        // A heading inside the body: the author writes the BODY only; headings
        // are set deterministically at assembly.
        for (int i = text.indexOf("## "); i >= 0; i = text.indexOf("## ", i + 1)) {
            if (i == 0 || text.charAt(i - 1) == '\n') {
                out.add(new Objection(around(text, i), de
                        ? "Überschriftenzeile im Abschnittskörper"
                        : "heading line inside the section body", Objection.Kind.RESIDUE));
                break;
            }
        }
        // Brackets other than [n] source markers: prose carries none — a 4B
        // occasionally mints pseudo-citations from material timestamps
        // (live-observed smoke run: "[2026-07-13 138.28]" instead of the
        // room's real marker).
        Matcher bracket = NON_MARKER_BRACKET.matcher(text);
        if (bracket.find()) {
            out.add(new Objection(bracket.group(), de
                    ? "Klammerinhalt, der kein Quellenmarker ist - nur die [n]-Marker "
                            + "des Materials zitieren"
                    : "bracket content that is not a source marker — cite the material's "
                            + "[n] markers only", Objection.Kind.RESIDUE));
        }
    }

    // -- marker discipline --

    private static void markerObjections(String text, Set<Integer> allowed, boolean de,
            List<Objection> out) {
        Set<Integer> bad = new LinkedHashSet<>();
        Matcher m = MARKER.matcher(text);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (allowed == null || !allowed.contains(n)) bad.add(n);
        }
        if (!bad.isEmpty()) {
            out.add(new Objection("[" + bad.iterator().next() + "]", de
                    ? "Quellenmarker " + bad + " gehören nicht zum Material dieses Abschnitts"
                    : "source marker(s) " + bad + " do not belong to this section's material",
                    Objection.Kind.MARKER));
        }
    }

    // -- sentence integrity --

    private static void integrityObjections(String text, boolean de, List<Objection> out) {
        for (String rawPara : text.split("\n\\s*\n")) {
            String para = rawPara.strip();
            if (para.isEmpty()) continue;
            // Table blocks are typesetting, not sentences — a pipe row
            // legitimately ends on '|'; the end-like-a-sentence check runs
            // over the paragraph's prose lines only.
            if (containsTableLine(para)) {
                para = withoutTableLines(para).strip();
                if (para.isEmpty()) continue;
            }
            // Trailing source markers are legitimate after the closing punctuation.
            String stripped = para.replaceAll("(\\s*\\[\\d{1,2}])+\\s*$", "").stripTrailing();
            if (stripped.isEmpty()) continue;
            char last = stripped.charAt(stripped.length() - 1);
            if (last != '.' && last != '!' && last != '?' && last != '…'
                    && last != '"' && last != '“' && last != '”' && last != ')') {
                out.add(new Objection(tail(stripped), de
                        ? "Absatz endet nicht wie ein Satz (zerrissener Text)"
                        : "paragraph does not end like a sentence (torn text)",
                        Objection.Kind.INTEGRITY));
            }
        }
        // A word truncated INTO an ellipsis mid-sentence ("ändern mu…." —
        // live run 8: a cut source snippet copied verbatim into prose).
        Matcher truncated = TRUNCATED_WORD.matcher(text);
        if (truncated.find()) {
            out.add(new Objection(around(text, truncated.start()), de
                    ? "Wort bricht in eine Ellipse ab (zerrissener Quellen-Schnipsel "
                            + "in der Prosa)"
                    : "word truncated into an ellipsis (torn source snippet copied into prose)",
                    Objection.Kind.INTEGRITY));
        }
    }

    /** A letter directly followed by "…" then a period or a lowercase continuation. */
    private static final Pattern TRUNCATED_WORD = Pattern.compile("\\p{L}…(\\.|\\s+\\p{Ll})");

    // -- spelled-out numerals (German) --

    /**
     * German number-word atoms for full-decomposition matching, longest first.
     * A token counts as a spelled numeral only when it decomposes COMPLETELY
     * into these atoms (optionally minus an ordinal/inflection suffix) — so
     * "Achtung", "Elfmeter" or "Jahrhundert" never match.
     */
    private static final String[] NUM_ATOMS = {
            "dreizehn", "vierzehn", "fünfzehn", "sechzehn", "siebzehn", "achtzehn",
            "neunzehn", "zwanzig", "dreißig", "vierzig", "fünfzig", "sechzig",
            "siebzig", "achtzig", "neunzig", "hundert", "tausend",
            "sieben", "sechs", "zwölf", "eins", "zwei", "drei", "vier", "fünf",
            "acht", "neun", "zehn", "null", "elf", "ein", "und"
    };
    /** Atoms that make a decomposed token a FIGURE (not a small count word). */
    private static final Set<String> NUM_SIGNAL_ATOMS = Set.of(
            "zwanzig", "dreißig", "vierzig", "fünfzig", "sechzig", "siebzig",
            "achtzig", "neunzig", "hundert", "tausend");
    private static final String[] NUM_SUFFIXES = {"sten", "ster", "stes", "ste",
            "ten", "ter", "tes", "te", "er"};
    private static final Pattern WORD_TOKEN = Pattern.compile("\\p{L}+");
    /** A spelled decimal: a numeral word, "Komma", a numeral word. */
    private static final Pattern SPELLED_DECIMAL = Pattern.compile(
            "(?iu)\\b(\\p{L}+)\\s+Komma\\s+(\\p{L}+)\\b");
    /**
     * A spelled decimal via COMMA SPLICE: numeral word, comma, numeral word,
     * then a scale/currency/unit word right after (live run: "8,5 Millionen"
     * verbalized as "acht, fünf Millionen Aktien" — invisible to the figure
     * check). The scale word is the disambiguation gate: a plain enumeration
     * ("acht, fünf und drei Punkte") never carries one there.
     */
    private static final Pattern SPELLED_COMMA_DECIMAL = Pattern.compile(
            "(?iu)\\b(\\p{L}+),\\s*(\\p{L}+)\\s+(Million(?:en)?|Milliarden?|Mrd\\.?|Mio\\.?|"
                    + "Tausend|Prozent|Dollar|Euro|EUR|USD)\\b");
    /** A spelled date day: a numeral word (opt. dot) directly before a month name. */
    private static final Pattern SPELLED_DATE = Pattern.compile(
            "(?iu)\\b(\\p{L}+)\\.?\\s+(Januar|Februar|März|April|Mai|Juni|Juli|"
                    + "August|September|Oktober|November|Dezember)\\b");

    /**
     * Values spelled out as German number words are INVISIBLE to the figure
     * check — the one rendering of a figure the examiner cannot reconcile
     * against the material, and therefore forbidden in report prose (digits
     * only; the section prompt says so). Live run 8: the model systematically
     * evaded figure objections by writing "vierundsiebzig Komma fünf Prozent"
     * (factually wrong — 47,5), "zweitausendsechsundzwanzig" for a 2027 date,
     * garbled compounds ("Seinhundertsechsunddreißig"). Simple standalone
     * count words up to "zwölf" stay exempt, mirroring the small-int rule.
     */
    private static void numberWordObjections(String text, List<Objection> out) {
        // Detection runs PER SENTENCE — a needle-anchored lookup flags only
        // the FIRST occurrence's sentence, and a twin sentence spelling out
        // the same words survives the surgery unexamined (live run 9: two
        // "dreizehnten Juli zweitausendsechsundzwanzig" sentences, one cut).
        int flagged = 0;
        for (String sentence : sentences(text)) {
            if (flagged >= 6) break;
            if (containsSpelledNumber(sentence)) {
                out.add(new Objection(sentence.strip(),
                        "Wert als Zahlwörter ausgeschrieben - jede Zahl, jeden Tag und "
                                + "jedes Jahr in ZIFFERN schreiben (nur Monatsnamen als "
                                + "Wörter), damit sie prüfbar sind",
                        Objection.Kind.NUMBER_WORDS));
                flagged++;
            }
        }
    }

    private static boolean containsSpelledNumber(String sentence) {
        Matcher word = WORD_TOKEN.matcher(sentence);
        while (word.find()) {
            if (isSpelledFigure(word.group())) return true;
        }
        Matcher decimal = SPELLED_DECIMAL.matcher(sentence);
        while (decimal.find()) {
            if (decomposeNumeral(decimal.group(1)) != null) return true;
        }
        // "acht, fünf Millionen": both halves must be numerals AND the scale
        // word follows directly — a plain enumeration stays legitimate prose.
        Matcher comma = SPELLED_COMMA_DECIMAL.matcher(sentence);
        while (comma.find()) {
            if (decomposeNumeral(comma.group(1)) != null
                    && decomposeNumeral(comma.group(2)) != null) {
                return true;
            }
        }
        Matcher date = SPELLED_DATE.matcher(sentence);
        while (date.find()) {
            if (decomposeNumeral(date.group(1)) != null) return true;
        }
        return false;
    }

    /** A compound numeral, a tens word or larger — a figure hiding as words. */
    private static boolean isSpelledFigure(String token) {
        List<String> atoms = decomposeNumeral(token);
        if (atoms == null || atoms.isEmpty()) return false;
        if (atoms.size() >= 2) return true;
        return NUM_SIGNAL_ATOMS.contains(atoms.get(0));
    }

    /**
     * Full decomposition of a token into number-word atoms (null when the
     * token is not a pure numeral). An ordinal/inflection suffix may ride at
     * the end ("dreizehnten", "zwanzigste").
     */
    private static List<String> decomposeNumeral(String token) {
        String t = token.toLowerCase(Locale.ROOT);
        List<String> direct = decomposeExact(t);
        if (direct != null) return direct;
        for (String suffix : NUM_SUFFIXES) {
            if (t.length() > suffix.length() && t.endsWith(suffix)) {
                List<String> stripped = decomposeExact(t.substring(0, t.length() - suffix.length()));
                if (stripped != null) return stripped;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> decomposeExact(String t) {
        if (t.isEmpty()) return null;
        int n = t.length();
        List<String>[] dp = new List[n + 1];
        dp[0] = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (dp[i] == null) continue;
            for (String atom : NUM_ATOMS) {
                if (t.startsWith(atom, i) && dp[i + atom.length()] == null) {
                    List<String> next = new ArrayList<>(dp[i]);
                    next.add(atom);
                    dp[i + atom.length()] = next;
                }
            }
        }
        return dp[n];
    }

    // -- repetition --

    private static void repeatObjections(String text, boolean de, List<Objection> out) {
        // Table rows are exempt from the sentence pairing: a licensed table
        // legitimately shares its tokens with the introducing prose around it
        // (and a periodless row block would otherwise lump into one giant
        // "sentence" and near-match its neighbours).
        text = withoutTableLines(text);
        List<String> normed = new ArrayList<>();
        for (String sentence : sentences(text)) {
            String norm = sentence.strip().replaceAll("\\s+", " ");
            if (norm.length() < MIN_REPEAT_SENTENCE_CHARS) continue;
            if (normed.contains(norm)) {
                out.add(new Objection(head(sentence), de
                        ? "Satz wiederholt sich wortgleich innerhalb des Abschnitts"
                        : "sentence repeats verbatim inside the section",
                        Objection.Kind.REPEAT));
                continue;
            }
            // NEAR repeats too: the weave appends the same story from a second
            // source as a fresh paragraph instead of confirming the standing
            // one (live run 11: the BLA resubmission told three times in Lage,
            // reworded each time — verbatim equality never fired).
            for (int i = 0; i < normed.size(); i++) {
                if (tokenJaccard(norm, normed.get(i)) >= NEAR_REPEAT_SIMILARITY) {
                    out.add(new Objection(head(sentence), de
                            ? "Satz erzählt eine Aussage nach, die der Abschnitt bereits "
                                    + "trägt - beide zu EINEM Satz zusammenführen (dort den "
                                    + "Marker der zweiten Quelle ergänzen)"
                            : "sentence retells a statement the section already carries — merge "
                                    + "them into ONE (add the second source's marker there)",
                            Objection.Kind.REPEAT));
                    break;
                }
            }
            normed.add(norm);
        }
    }

    /** Token-set similarity above this = the same statement reworded. */
    private static final double NEAR_REPEAT_SIMILARITY = 0.6;

    private static double tokenJaccard(String a, String b) {
        Set<String> ta = tokenSet(a);
        Set<String> tb = tokenSet(b);
        if (ta.isEmpty() || tb.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(ta);
        inter.retainAll(tb);
        Set<String> union = new HashSet<>(ta);
        union.addAll(tb);
        return (double) inter.size() / union.size();
    }

    private static Set<String> tokenSet(String s) {
        Set<String> out = new HashSet<>();
        for (String t : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (t.length() >= 3) out.add(t);
        }
        return out;
    }

    // -- figure/date fidelity --

    private static void figureObjections(String text, String material, boolean germanText,
            boolean mixedLocaleMaterial, List<Objection> out) {
        if (material == null) material = "";
        Set<Double> materialNumbers = numberSet(material, false);
        if (mixedLocaleMaterial) materialNumbers.addAll(numberSet(material, true));
        Set<LocalDate> materialDates = materialDates(material);
        // Dates first — a matched date's digits are then exempt from the figure check.
        String remaining = text;
        for (DateHit hit : textDates(text)) {
            if (hit.date() != null && !materialDates.contains(hit.date())) {
                out.add(new Objection(hit.raw(), germanText
                        ? "Datum ist im verifizierten Material nicht vorhanden"
                        : "date not present in the verified material", Objection.Kind.DATE));
            }
            remaining = remaining.replace(hit.raw(), " ");
        }
        // Markers are citations, not figures.
        remaining = MARKER.matcher(remaining).replaceAll(" ");
        for (NumberHit hit : textNumbers(remaining, germanText)) {
            if (exempt(hit)) continue;
            if (!matches(hit.value(), materialNumbers)) {
                out.add(new Objection(sentenceAround(text, hit.raw()), germanText
                        ? "Zahl '" + hit.raw() + "' ist im verifizierten Material "
                                + "nicht vorhanden"
                        : "figure '" + hit.raw() + "' not present in the verified material",
                        Objection.Kind.FIGURE));
            }
        }
        // The number wall: the charts carry the series, the prose keeps the
        // load-bearing few — a section reciting a dozen values reads like a
        // data dump, not a note. A licensed table is the values' sanctioned
        // home: its cells never count toward the PROSE number wall
        // (figureCount strips table lines).
        int figures = figureCount(text, germanText);
        if (figures > MAX_FIGURES_PER_SECTION) {
            out.add(new Objection(head(text), germanText
                    ? "Zahlenwand: " + figures + " Zahlen in einem Abschnitt - pro Absatz "
                            + "nur die zwei, drei tragenden Werte behalten, die Serien "
                            + "tragen die Diagramme"
                    : "number wall: " + figures + " figures in one section — keep the two or "
                            + "three load-bearing values per paragraph, the charts carry "
                            + "the series", Objection.Kind.DENSITY));
        }
    }

    /**
     * The section's figure count under the density rules — dates and markers
     * are not figures, small ints and years are exempt, and TABLE cells never
     * count (a licensed table is the values' sanctioned home; the density rule
     * governs the prose). The copy-editor pass uses this to decide whether a
     * number-wall note rides along.
     */
    static int figureCount(String text, boolean german) {
        if (text == null || text.isBlank()) return 0;
        String remaining = withoutTableLines(text);
        for (DateHit hit : textDates(text)) {
            remaining = remaining.replace(hit.raw(), " ");
        }
        remaining = MARKER.matcher(remaining).replaceAll(" ");
        int figures = 0;
        for (NumberHit hit : textNumbers(remaining, german)) {
            if (!exempt(hit)) figures++;
        }
        return figures;
    }

    private record NumberHit(String raw, double value) {
    }

    private record DateHit(String raw, LocalDate date) {
    }

    private static boolean exempt(NumberHit hit) {
        double v = Math.abs(hit.value());
        boolean integer = v == Math.rint(v) && !hit.raw().contains(",") && !hit.raw().contains(".");
        if (integer && v <= SMALL_INT_EXEMPT) return true;
        return integer && v >= 1900 && v <= 2100; // a year
    }

    private static boolean matches(double v, Set<Double> materialNumbers) {
        double a = Math.abs(v);
        for (double mv : materialNumbers) {
            double b = Math.abs(mv);
            for (double scale : SCALES) {
                if (close(a * scale, b) || close(a, b * scale)) return true;
            }
        }
        return false;
    }

    private static boolean close(double a, double b) {
        double tol = Math.max(REL_TOLERANCE * Math.max(Math.abs(a), Math.abs(b)), 0.005);
        return Math.abs(a - b) <= tol;
    }

    /** Every number token of a text as normalized doubles. */
    static Set<Double> numberSet(String text, boolean german) {
        Set<Double> out = new HashSet<>();
        for (NumberHit hit : textNumbers(MARKER.matcher(text).replaceAll(" "), german)) {
            out.add(hit.value());
        }
        return out;
    }

    private static List<NumberHit> textNumbers(String text, boolean german) {
        List<NumberHit> out = new ArrayList<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            String raw = m.group();
            Double v = parseNumber(raw, german);
            if (v != null) out.add(new NumberHit(raw.strip(), v));
        }
        return out;
    }

    /**
     * Locale-aware token parse. German: '.'/space/NBSP group, ',' decimal.
     * ROOT (material): space/NBSP group, '.' decimal, ',' group (raw upstream
     * strings occasionally carry comma grouping). A token with several dots and
     * no comma in German is a date fragment/ordinal chain — skipped (dates are
     * checked separately).
     */
    private static Double parseNumber(String raw, boolean german) {
        String s = raw.strip().replace("−", "-").replace(" ", " ");
        boolean negative = s.startsWith("-");
        if (s.startsWith("+") || s.startsWith("-")) s = s.substring(1);
        s = s.replace(" ", "");
        if (s.isEmpty()) return null;
        try {
            double v;
            if (german) {
                long dots = s.chars().filter(c -> c == '.').count();
                if (s.contains(",")) {
                    v = Double.parseDouble(s.replace(".", "").replace(',', '.'));
                } else if (dots >= 1) {
                    // Dots without a comma: GROUPING only when the shape is a
                    // grouped integer (1-3 lead digits, then exactly three per
                    // segment — "36.800", "1.243.743"); anything else is a date
                    // fragment or ordinal chain ("13.07.2026") and no figure.
                    // The old blanket dots>1 → null let an unparseable
                    // "1.243.743" sail past the figure check entirely (live
                    // run 10: Tradegate volume misstated by 10x, unflagged).
                    if (isDotGroupedInteger(s)) v = Double.parseDouble(s.replace(".", ""));
                    else return null;
                } else {
                    v = Double.parseDouble(s);
                }
            } else {
                long dots = s.chars().filter(c -> c == '.').count();
                if (dots > 1) return null;
                v = Double.parseDouble(s.replace(",", ""));
            }
            return negative ? -v : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "36.800" / "1.243.743": 1-3 lead digits, then exactly 3 per dot segment. */
    private static boolean isDotGroupedInteger(String s) {
        String[] parts = s.split("\\.");
        if (parts.length < 2 || parts[0].isEmpty()
                || parts[0].length() > 3 || !parts[0].chars().allMatch(Character::isDigit)) {
            return false;
        }
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].length() != 3 || !parts[i].chars().allMatch(Character::isDigit)) {
                return false;
            }
        }
        return true;
    }

    private static List<DateHit> textDates(String text) {
        List<DateHit> out = new ArrayList<>();
        Matcher dotted = DOTTED_DATE.matcher(text);
        while (dotted.find()) {
            out.add(new DateHit(dotted.group(), safeDate(
                    Integer.parseInt(dotted.group(3)), Integer.parseInt(dotted.group(2)),
                    Integer.parseInt(dotted.group(1)))));
        }
        Matcher word = WORD_DATE.matcher(text);
        while (word.find()) {
            Integer month;
            int day;
            int year;
            if (word.group(2) != null) {
                month = MONTHS.get(word.group(2).toLowerCase(Locale.ROOT));
                day = Integer.parseInt(word.group(1));
                year = Integer.parseInt(word.group(3));
            } else {
                month = MONTHS.get(word.group(4).toLowerCase(Locale.ROOT));
                day = Integer.parseInt(word.group(5));
                year = Integer.parseInt(word.group(6));
            }
            if (month == null) continue; // not a month word — leave to the figure check
            out.add(new DateHit(word.group(), safeDate(year, month, day)));
        }
        return out;
    }

    /** ISO plus dotted dates in the material (our block renderers emit ISO). */
    private static Set<LocalDate> materialDates(String material) {
        Set<LocalDate> out = new HashSet<>();
        Matcher iso = ISO_DATE.matcher(material);
        while (iso.find()) {
            LocalDate d = safeDate(Integer.parseInt(iso.group(1)),
                    Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
            if (d != null) out.add(d);
        }
        Matcher dotted = DOTTED_DATE.matcher(material);
        while (dotted.find()) {
            LocalDate d = safeDate(Integer.parseInt(dotted.group(3)),
                    Integer.parseInt(dotted.group(2)), Integer.parseInt(dotted.group(1)));
            if (d != null) out.add(d);
        }
        for (DateHit hit : textDates(material)) {
            if (hit.date() != null) out.add(hit.date());
        }
        return out;
    }

    private static LocalDate safeDate(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day);
        } catch (Exception e) {
            return null;
        }
    }

    // -- sentence surgery (the last-resort removal of a refuted figure) --

    /**
     * Removes every sentence that contains one of the objections' quotes —
     * the deterministic last resort after revision failed to fix a hard
     * figure/date objection. Returns the surviving text (paragraph structure
     * kept; a paragraph whose every sentence fell is dropped).
     */
    static String removeOffendingSentences(String text, List<Objection> hard) {
        if (text == null || hard.isEmpty()) return text;
        List<String> needles = hard.stream()
                .map(Objection::quote)
                .filter(q -> q != null && !q.isBlank())
                .toList();
        StringBuilder out = new StringBuilder(text.length());
        for (String rawPara : text.split("\n\\s*\n")) {
            String para = rawPara.strip();
            if (para.isEmpty()) continue;
            // Surgery on a table block works ROW-wise: the offending line
            // falls, the block's line structure (its typesetting) survives —
            // never sentence-join pipe rows into one line.
            if (containsTableLine(para)) {
                StringBuilder keptLines = new StringBuilder(para.length());
                for (String line : para.split("\n")) {
                    if (offends(line, needles)) continue;
                    if (keptLines.length() > 0) keptLines.append('\n');
                    keptLines.append(line);
                }
                if (keptLines.length() > 0) {
                    if (out.length() > 0) out.append("\n\n");
                    out.append(keptLines);
                }
                continue;
            }
            StringBuilder kept = new StringBuilder(para.length());
            for (String sentence : sentences(para)) {
                if (!offends(sentence, needles)) {
                    if (kept.length() > 0) kept.append(' ');
                    kept.append(sentence.strip());
                }
            }
            if (kept.length() > 0) {
                if (out.length() > 0) out.append("\n\n");
                out.append(kept);
            }
        }
        return out.toString();
    }

    /** Whether one sentence/row matches any objection quote (both ways, ellipsis-tolerant). */
    private static boolean offends(String segment, List<String> needles) {
        String norm = segment.replaceAll("\\s+", " ");
        for (String needle : needles) {
            // Objection quotes may be head()-truncated with a trailing
            // ellipsis — strip it so the prefix still matches.
            String needleNorm = needle.replaceAll("\\s+", " ")
                    .replaceAll("…$", "").replaceAll("^…", "");
            if (needleNorm.isBlank()) continue;
            if (norm.contains(needleNorm) || needleNorm.contains(norm)) return true;
        }
        return false;
    }

    /**
     * Deterministic residue belt: drops every LINE that carries a forbidden
     * protocol/meta token — the last resort when a revision left one standing
     * (the old edit-script sentinels reached the archive once; a scrubbed line
     * beats a leaked protocol artifact).
     */
    static String scrubResidue(String text) {
        if (text == null || text.isEmpty()) return text;
        boolean dirty = false;
        for (String bad : FORBIDDEN) {
            if (text.contains(bad)) {
                dirty = true;
                break;
            }
        }
        if (!dirty) return text;
        StringBuilder out = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            boolean drop = false;
            for (String bad : FORBIDDEN) {
                if (line.contains(bad)) {
                    drop = true;
                    break;
                }
            }
            if (!drop) out.append(line).append('\n');
        }
        return out.toString().strip();
    }

    /**
     * Deterministically removes non-marker brackets from prose — the belt
     * behind the examiner's objection when a revision left a pseudo-citation
     * standing (live-observed: "[2026-07-13 18:51]" survived two rounds).
     * The sentence itself stays; double spaces collapse.
     */
    static String scrubNonMarkerBrackets(String text) {
        if (text == null || text.isEmpty()) return text;
        Matcher m = NON_MARKER_BRACKET.matcher(text);
        if (!m.find()) return text;
        return m.reset().replaceAll("").replaceAll("[ \\t]{2,}", " ")
                .replaceAll(" +([.,;:!?])", "$1");
    }

    // -- torn source-list residue (the weave copies raw list lines) --

    /** A [YYYY-MM-DD …] timestamp bracket as the news-list blocks print it. */
    private static final Pattern LIST_DATE_BRACKET =
            Pattern.compile("\\[\\d{4}-\\d{2}-\\d{2}[^\\]\\n]*]");
    /** Line head: optional dash/bullet, then any run of [n] markers. */
    private static final Pattern LIST_LINE_HEAD =
            Pattern.compile("^(?:[-–—•]\\s+)?(?:\\[\\d{1,2}]\\s*)*");
    /** The doubled-identical-marker echo head ("[17] [17] Titel."). */
    private static final Pattern DOUBLED_MARKER_HEAD =
            Pattern.compile("^(?:[-–—•]\\s+)?\\[(\\d{1,2})]\\s*\\[(\\d{1,2})]");
    /** Sentence-leading [n] markers — markers belong at sentence/paragraph ENDS. */
    private static final Pattern LEADING_MARKERS =
            Pattern.compile("^(?:\\s*\\[\\d{1,2}])+\\s*");

    /**
     * Deterministic belt against RAW SOURCE-LIST lines copied into a body
     * (digest-less sources make the weave model paste its input list —
     * live-observed OTLK run: "- [19] [2026-07-12 09:26] Titel · Börse
     * Express" and "[17] [17] Titel." reached the body and burned
     * objection/revision rounds). Whole lines that are list-shaped are
     * removed: a (bulleted) head of markers followed by a [YYYY-MM-DD …]
     * timestamp bracket, a doubled identical marker head, or a "· Publisher"
     * tail without sentence-final punctuation. Sentence-LEADING markers on a
     * surviving line are dropped (never relocated); whitespace damage
     * collapses.
     */
    static String scrubSourceListLines(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length());
        boolean dirty = false;
        for (String line : text.split("\n", -1)) {
            // A table row legitimately carries markers and "·"-joined cell text
            // without sentence-final punctuation — NEVER a source-list line.
            if (isTableLine(line)) {
                out.append(line).append('\n');
                continue;
            }
            if (isSourceListLine(line)) {
                dirty = true;
                continue;
            }
            Matcher lead = LEADING_MARKERS.matcher(line);
            if (lead.lookingAt() && lead.end() > 0) {
                String rest = line.substring(lead.end());
                dirty = true;
                if (rest.isBlank()) continue; // a marker-only line says nothing
                line = rest;
            }
            out.append(line).append('\n');
        }
        if (!dirty) return text;
        return out.toString().replaceAll("\n{3,}", "\n\n").strip();
    }

    private static boolean isSourceListLine(String line) {
        String s = line.strip();
        if (s.isEmpty()) return false;
        Matcher head = LIST_LINE_HEAD.matcher(s);
        head.lookingAt(); // always matches (possibly empty)
        // A timestamp bracket right after the (bulleted) marker head — the
        // news-list block's own shape.
        if (LIST_DATE_BRACKET.matcher(s.substring(head.end()).stripLeading()).lookingAt()) {
            return true;
        }
        // The doubled IDENTICAL marker echo ("[17] [17] …"); different leading
        // markers stay a prose defect handled by the leading-marker strip.
        Matcher doubled = DOUBLED_MARKER_HEAD.matcher(s);
        if (doubled.lookingAt() && doubled.group(1).equals(doubled.group(2))) return true;
        // A "· Publisher" tail (trailing markers tolerated) that does not end
        // like a sentence — the list line's title/publisher form.
        if (s.contains(" · ")) {
            String tail = s.replaceAll("(\\s*\\[\\d{1,2}])+\\s*$", "").stripTrailing();
            if (!tail.isEmpty()) {
                char last = tail.charAt(tail.length() - 1);
                if (last != '.' && last != '!' && last != '?' && last != '…') return true;
            }
        }
        return false;
    }

    // -- shared text helpers --

    /**
     * Sentence-ish segments: cut after {@code .!?} followed by whitespace and
     * an upper-case/digit start (German ordinals "am 24. Juli" survive intact,
     * unlike a naive period split — the naive split is exactly what blinded
     * the old dedupe).
     */
    static List<String> sentences(String block) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < block.length(); i++) {
            char c = block.charAt(i);
            if (c != '.' && c != '!' && c != '?') continue;
            int j = i + 1;
            // Trailing quotes/brackets/markers belong to the sentence.
            while (j < block.length() && "\"“”)]".indexOf(block.charAt(j)) >= 0) j++;
            int k = j;
            while (k < block.length() && Character.isWhitespace(block.charAt(k))) k++;
            if (k == j) continue; // no whitespace after — mid-token period
            if (k < block.length()) {
                char next = block.charAt(k);
                boolean sentenceStart = Character.isUpperCase(next) || next == '[' || next == '"'
                        || next == '„' || next == '“';
                // "am 24. Juli": the char after an ordinal's whitespace is upper-case
                // too (Juli) — treat a SHORT all-digit token before the period as an
                // ordinal and keep going.
                if (sentenceStart && isOrdinalBefore(block, i)) continue;
                if (sentenceStart && isAbbreviationBefore(block, i)) continue;
                if (!sentenceStart) continue;
            }
            out.add(block.substring(start, j));
            start = k;
            i = k - 1;
        }
        if (start < block.length() && !block.substring(start).isBlank()) {
            out.add(block.substring(start));
        }
        return out;
    }

    /**
     * Unit/abbreviation periods are never sentence ends: "3,15 Mrd. EUR" split
     * after "Mrd." tore a figure (and its bold span) across the deterministic
     * paragraph break (live SAP run 2026-07-14). Kept to a tight, unambiguous
     * set — a real sentence ending on one of these words is vanishingly rare
     * in the report register.
     */
    private static final java.util.Set<String> ABBREVIATIONS = java.util.Set.of(
            "mrd", "mio", "tsd", "bzw", "ca", "inkl", "zzgl", "ggf", "evtl",
            "nr", "co", "inc", "ltd", "dr", "prof", "vs");

    /** True when the period at {@code idx} terminates a known abbreviation. */
    private static boolean isAbbreviationBefore(String s, int idx) {
        int i = idx - 1;
        while (i >= 0 && Character.isLetter(s.charAt(i))) i--;
        if (i == idx - 1) return false;
        // Must be a standalone token, not a word tail ("…Reviews." stays an end).
        String word = s.substring(i + 1, idx).toLowerCase(Locale.ROOT);
        if (!ABBREVIATIONS.contains(word)) return false;
        return i < 0 || !Character.isLetterOrDigit(s.charAt(i));
    }

    /** True when the period at {@code idx} terminates a 1-2 digit ordinal ("2.", "24."). */
    private static boolean isOrdinalBefore(String s, int idx) {
        int d = 0;
        int i = idx - 1;
        while (i >= 0 && Character.isDigit(s.charAt(i))) {
            d++;
            i--;
        }
        if (d >= 1 && d <= 2 && (i < 0 || !Character.isLetterOrDigit(s.charAt(i)))) return true;
        // A NUMBER-WORD ordinal before a month name ("am acht. Juli") must not
        // split either — the fragment boundary hid the spelled date from the
        // number-word detector (live run 11: "am acht. Juli 2026" survived).
        if (d > 0) return false;
        int end = idx;
        while (i >= 0 && Character.isLetter(s.charAt(i))) i--;
        String word = s.substring(i + 1, end);
        if (word.isEmpty() || decomposeNumeral(word.toLowerCase(Locale.ROOT)) == null) {
            return false;
        }
        // Only when a month name follows — "Es waren acht." stays a sentence end.
        int k = idx + 1;
        while (k < s.length() && Character.isWhitespace(s.charAt(k))) k++;
        int wordEnd = k;
        while (wordEnd < s.length() && Character.isLetter(s.charAt(wordEnd))) wordEnd++;
        return k < wordEnd && MONTHS.containsKey(
                s.substring(k, wordEnd).toLowerCase(Locale.ROOT));
    }

    /** The sentence of {@code text} containing {@code needle} (for objection quotes). */
    private static String sentenceAround(String text, String needle) {
        // In a table the quotable unit is the ROW — a periodless block would
        // otherwise read as one giant "sentence" and drag innocent rows into
        // the objection quote (and later into the surgery).
        for (String line : text.split("\n")) {
            if (isTableLine(line) && line.contains(needle)) return head(line.strip());
        }
        for (String rawPara : text.split("\n\\s*\n")) {
            for (String sentence : sentences(rawPara)) {
                if (sentence.contains(needle)) return head(sentence);
            }
        }
        return needle;
    }

    private static String head(String s) {
        String flat = s.strip().replaceAll("\\s+", " ");
        return flat.length() <= 160 ? flat : flat.substring(0, 157) + "…";
    }

    private static String tail(String s) {
        String flat = s.strip().replaceAll("\\s+", " ");
        return flat.length() <= 120 ? flat : "…" + flat.substring(flat.length() - 117);
    }

    private static String around(String text, int idx) {
        int from = Math.max(0, idx - 40);
        int to = Math.min(text.length(), idx + 60);
        return text.substring(from, to).replaceAll("\\s+", " ");
    }
}
