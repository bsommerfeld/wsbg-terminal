package de.bsommerfeld.wsbg.terminal.finanzennet;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A minimal reader for finanzen.net's server-rendered tables plus the German
 * number/date grammar they are written in. No DOM library: every instrument
 * page is one flat {@code <table class="table">} per block, always preceded by
 * its own {@code <h2>} headline, which is enough structure to key off.
 *
 * <p><b>German formatting is the trap here.</b> A finanzen.net cell reads
 * {@code 22.631} for twenty-two thousand and {@code 159,00 EUR} for a price -
 * the dot is the THOUSANDS separator and the comma the DECIMAL point, the exact
 * inverse of the machine convention. A naive {@code Double.parseDouble} turns a
 * volume of 22.631 shares into 22.6. {@link #germanNumber} handles both plus the
 * {@code +}/{@code −} signs, the {@code %} suffix, the currency suffix and the
 * literal {@code -} that means "no value".
 */
final class HtmlTables {

    private static final Pattern TABLE = Pattern.compile("<table\\b[^>]*>(.*?)</table>", Pattern.DOTALL);
    private static final Pattern ROW = Pattern.compile("<tr\\b[^>]*>(.*?)</tr>", Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<t[dh]\\b[^>]*>(.*?)</t[dh]>", Pattern.DOTALL);
    private static final Pattern HEADLINE = Pattern.compile("<h[1-4]\\b[^>]*>(.*?)</h[1-4]>", Pattern.DOTALL);
    private static final Pattern HREF = Pattern.compile("href\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    /** German date, e.g. {@code 31.07.2026}. */
    private static final DateTimeFormatter DE_DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY);
    /** Two-digit German date as printed on the analysis pages, e.g. {@code 31.07.26}. */
    private static final DateTimeFormatter DE_DATE_SHORT =
            DateTimeFormatter.ofPattern("dd.MM.yy", Locale.GERMANY);

    private HtmlTables() {}

    /**
     * One parsed table: the {@code <h2>} standing immediately above it plus its
     * rows as plain cell text (first row is usually the header row).
     *
     * @param heading the nearest preceding headline, "" when there is none
     * @param rows    rows of already-unescaped, tag-stripped cell text
     * @param links   per row, the {@code href} of the row's first anchor, or null
     */
    record Table(String heading, List<List<String>> rows, List<String> links) {

        /** The row whose first cell equals {@code label} (case/space-insensitive). */
        List<String> row(String label) {
            for (List<String> r : rows) {
                if (!r.isEmpty() && normalizeLabel(r.get(0)).equals(normalizeLabel(label))) return r;
            }
            return List.of();
        }

        /** The row whose first cell STARTS WITH {@code prefix}. */
        List<String> rowStartingWith(String prefix) {
            for (List<String> r : rows) {
                if (!r.isEmpty() && normalizeLabel(r.get(0)).startsWith(normalizeLabel(prefix))) return r;
            }
            return List.of();
        }

        /** Cell {@code i} of {@code row}, or null when the row is short. */
        static String cell(List<String> row, int i) {
            return row.size() > i ? row.get(i) : null;
        }
    }

    /** Every table on the page, in document order. */
    static List<Table> parse(String html) {
        if (html == null || html.isBlank()) return List.of();
        List<Table> out = new ArrayList<>();
        Matcher t = TABLE.matcher(html);
        while (t.find()) {
            String heading = headingBefore(html, t.start());
            List<List<String>> rows = new ArrayList<>();
            List<String> links = new ArrayList<>();
            Matcher r = ROW.matcher(t.group(1));
            while (r.find()) {
                String rowHtml = r.group(1);
                List<String> cells = new ArrayList<>();
                Matcher c = CELL.matcher(rowHtml);
                while (c.find()) cells.add(text(c.group(1)));
                if (cells.isEmpty()) continue;
                rows.add(List.copyOf(cells));
                Matcher href = HREF.matcher(rowHtml);
                links.add(href.find() ? href.group(1) : null);
            }
            out.add(new Table(heading, List.copyOf(rows), links));
        }
        return List.copyOf(out);
    }

    /** The first table whose heading contains all of {@code needles}, or null. */
    static Table find(List<Table> tables, String... needles) {
        for (Table t : tables) {
            String h = FinanzenNetHttp.lower(t.heading());
            boolean all = true;
            for (String n : needles) {
                if (!h.contains(FinanzenNetHttp.lower(n))) { all = false; break; }
            }
            if (all) return t;
        }
        return null;
    }

    /** All tables whose heading contains all of {@code needles}. */
    static List<Table> findAll(List<Table> tables, String... needles) {
        List<Table> out = new ArrayList<>();
        for (Table t : tables) {
            String h = FinanzenNetHttp.lower(t.heading());
            boolean all = true;
            for (String n : needles) {
                if (!h.contains(FinanzenNetHttp.lower(n))) { all = false; break; }
            }
            if (all) out.add(t);
        }
        return List.copyOf(out);
    }

    private static String headingBefore(String html, int tableStart) {
        String before = html.substring(Math.max(0, tableStart - 2000), tableStart);
        String last = "";
        Matcher m = HEADLINE.matcher(before);
        while (m.find()) last = m.group(1);
        return text(last);
    }

    /** Strips tags, unescapes entities, collapses whitespace. */
    static String text(String html) {
        if (html == null) return "";
        String s = html.replaceAll("(?is)<br\\s*/?>", " ");
        s = TAG.matcher(s).replaceAll(" ");
        s = unescape(s);
        return s.replaceAll("\\s+", " ").trim();
    }

    /** The named/numeric entities finanzen.net actually emits. */
    static String unescape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch != '&') { out.append(ch); i++; continue; }
            int semi = s.indexOf(';', i);
            if (semi < 0 || semi - i > 10) { out.append(ch); i++; continue; }
            String ent = s.substring(i + 1, semi);
            String rep = switch (ent) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos", "#39" -> "'";
                case "nbsp", "#160" -> " ";
                case "euro" -> "€";
                default -> null;
            };
            if (rep == null && ent.startsWith("#")) {
                try {
                    int cp = ent.startsWith("#x") || ent.startsWith("#X")
                            ? Integer.parseInt(ent.substring(2), 16)
                            : Integer.parseInt(ent.substring(1));
                    rep = new String(Character.toChars(cp));
                } catch (RuntimeException ignored) {
                    rep = null;
                }
            }
            if (rep == null) { out.append(ch); i++; continue; }
            out.append(rep);
            i = semi + 1;
        }
        return out.toString();
    }

    /**
     * German number → double. {@code "159,00 EUR"} → 159.0,
     * {@code "22.631"} → 22631.0, {@code "+1,21%"} → 1.21,
     * {@code "-"} / blank / unparseable → null.
     */
    static Double germanNumber(String raw) {
        if (raw == null) return null;
        String s = raw.replace('\u2212', '-')   // U+2212 MINUS SIGN
                .replace('\u002B', '+')
                .replace("\u00A0", " ")
                .trim();
        if (s.isEmpty() || s.equals("-") || s.equals("--")) return null;
        Matcher m = Pattern.compile("[+-]?\\d{1,3}(?:\\.\\d{3})*(?:,\\d+)?|[+-]?\\d+(?:,\\d+)?")
                .matcher(s);
        if (!m.find()) return null;
        String num = m.group().replace(".", "").replace(',', '.');
        try {
            return Double.valueOf(num);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** German integer (volume, analyst counts) → long, or null. */
    static Long germanLong(String raw) {
        Double d = germanNumber(raw);
        return d == null ? null : d.longValue();
    }

    /** German integer → int with a {@code 0} default - counts are never null-shaped. */
    static int germanInt(String raw) {
        Double d = germanNumber(raw);
        return d == null ? 0 : (int) Math.round(d);
    }

    /** The currency token trailing a value cell ({@code EUR}, {@code USD}, {@code €}), or null. */
    static String currency(String raw) {
        if (raw == null) return null;
        Matcher m = Pattern.compile("\\b([A-Z]{3})\\b").matcher(raw);
        if (m.find()) return m.group(1);
        if (raw.contains("€")) return "EUR";
        if (raw.contains("$")) return "USD";
        return null;
    }

    /**
     * {@code 31.07.2026} or {@code 28.01.2027 (e)*} or {@code 31.07.26} →
     * {@link LocalDate}; anything else → null.
     */
    static LocalDate germanDate(String raw) {
        if (raw == null) return null;
        Matcher m = Pattern.compile("(\\d{2})\\.(\\d{2})\\.(\\d{2,4})").matcher(raw);
        if (!m.find()) return null;
        String s = m.group();
        try {
            return LocalDate.parse(s, m.group(3).length() == 4 ? DE_DATE : DE_DATE_SHORT);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code 21:57:30} / {@code 21:57} → {@link LocalTime}, or null. */
    static LocalTime clockTime(String raw) {
        if (raw == null) return null;
        Matcher m = Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?").matcher(raw);
        if (!m.find()) return null;
        try {
            return LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    m.group(3) == null ? 0 : Integer.parseInt(m.group(3)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String normalizeLabel(String s) {
        return FinanzenNetHttp.lower(s == null ? "" : s).replaceAll("\\s+", " ").trim();
    }
}
