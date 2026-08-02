package de.bsommerfeld.wsbg.terminal.boersede;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The shared HTML/date/number seam of the boerse.de module - the handful of
 * primitives BOTH the news client and the market client need, kept in one place
 * so a markup or format quirk is fixed once (probed live 2026-08-02).
 *
 * <p>boerse.de renders server-side Bootstrap markup with no JSON island, so
 * everything here is text surgery. Three quirks drive the shape of these
 * helpers:
 *
 * <ul>
 *   <li><b>Soft hyphens.</b> Table labels carry {@code &shy;} inside the word
 *       ({@code Umlauf&shy;verm&ouml;gen}), so a naive entity decode leaves an
 *       invisible U+00AD in the middle of every key. {@link #text(String)}
 *       drops it - a caller must be able to match {@code "Umlaufvermögen"}.</li>
 *   <li><b>Two date widths.</b> News lists print {@code 31.07.26}, the global
 *       tables print {@code 31.07.2026}. {@link #parseGermanDate(String)}
 *       swallows both.</li>
 *   <li><b>German numerals.</b> {@code 39.950} is thirty-nine thousand,
 *       {@code 2,70} is two point seven. {@link #parseGermanNumber(String)}
 *       reads them and answers {@code null} for the site's own "no value"
 *       tokens ({@code -}, {@code n.v.}).</li>
 * </ul>
 */
final class BoerseDeHtml {

    /** Publication timezone - boerse.de is a German venue and dates carry no clock. */
    static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private static final DateTimeFormatter D_SHORT =
            DateTimeFormatter.ofPattern("dd.MM.yy", Locale.GERMANY);
    private static final DateTimeFormatter D_LONG =
            DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY);
    /** The lead teaser prints its date as a bare {@code yyyyMMdd} run-together. */
    private static final DateTimeFormatter D_COMPACT =
            DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);

    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern WS = Pattern.compile("\\s+");
    private static final Pattern DATE_ANY =
            Pattern.compile("\\b(\\d{2}\\.\\d{2}\\.(?:\\d{4}|\\d{2}))\\b");
    private static final Pattern NUMERIC = Pattern.compile("-?[0-9.]+(?:,[0-9]+)?");

    /** {@code <a href="…/nachrichten/<slug>/<id>">} - the id is the article identity. */
    private static final Pattern ARTICLE_ID = Pattern.compile("/nachrichten/[^\"/]+/(\\d+)");
    /** {@code <a href="…/aktien/<slug>/<ISIN>">} - every global table links the instrument. */
    private static final Pattern INSTRUMENT_ISIN =
            Pattern.compile("/aktien/[^\"/]+/([A-Z]{2}[A-Z0-9]{9}\\d)");

    private BoerseDeHtml() {}

    /** Strips tags, decodes the entities boerse.de emits, collapses whitespace. */
    static String text(String html) {
        if (html == null) return "";
        String s = TAG.matcher(html).replaceAll(" ");
        s = decodeEntities(s);
        return WS.matcher(s).replaceAll(" ").trim();
    }

    /**
     * Decodes the named/numeric entities that actually appear on the site.
     * {@code &shy;} becomes nothing at all (see the class comment) and
     * {@code &nbsp;} becomes a plain space, so downstream matching never has to
     * know about invisible codepoints.
     */
    static String decodeEntities(String s) {
        if (s == null) return "";
        if (s.indexOf('&') < 0) return s;
        String prev;
        do {
            prev = s;
            s = s.replace("&shy;", "")
                    .replace("&nbsp;", " ")
                    .replace("&auml;", "ä").replace("&ouml;", "ö").replace("&uuml;", "ü")
                    .replace("&Auml;", "Ä").replace("&Ouml;", "Ö").replace("&Uuml;", "Ü")
                    .replace("&szlig;", "ß")
                    .replace("&ndash;", "-").replace("&mdash;", "-")
                    .replace("&euro;", "€")
                    .replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'")
                    .replace("&apos;", "'")
                    .replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&amp;", "&");
        } while (!s.equals(prev) && s.indexOf('&') >= 0);
        return s.replace("\u00ad", "");
    }

    /** {@code 31.07.26} / {@code 31.07.2026} → date; anything else → {@code null}. */
    static LocalDate parseGermanDate(String s) {
        if (s == null) return null;
        Matcher m = DATE_ANY.matcher(s);
        if (!m.find()) return null;
        String v = m.group(1);
        try {
            return LocalDate.parse(v, v.length() == 8 ? D_SHORT : D_LONG);
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code 20260728} (the lead teaser's run-together date) → date, else null. */
    static LocalDate parseCompactDate(String s) {
        if (s == null) return null;
        Matcher m = Pattern.compile("\\b(20\\d{6})\\b").matcher(s);
        if (!m.find()) return null;
        try {
            return LocalDate.parse(m.group(1), D_COMPACT);
        } catch (Exception e) {
            return null;
        }
    }

    /** German trading day → the UTC instant of its Berlin start-of-day; null-safe. */
    static Instant atBerlinStartOfDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay(ZONE).toInstant();
    }

    /**
     * German numeral → {@code double}. {@code .} is the thousands separator,
     * {@code ,} the decimal point. The site's own empty markers ({@code -},
     * {@code n.v.}, blank) answer {@code null} rather than a fake zero - a
     * missing balance-sheet figure must never read as "zero euro".
     */
    static Double parseGermanNumber(String s) {
        if (s == null) return null;
        String v = decodeEntities(s).replace("%", "").replace("\u00a0", " ").trim();
        if (v.isEmpty() || v.equals("-") || v.equalsIgnoreCase("n.v.") || v.equals("--")) return null;
        Matcher m = NUMERIC.matcher(v);
        if (!m.find()) return null;
        String num = m.group().replace(".", "").replace(",", ".");
        try {
            return Double.valueOf(num);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The numeric article id inside a {@code /nachrichten/…/<id>} link, else null. */
    static String articleId(String url) {
        if (url == null) return null;
        Matcher m = ARTICLE_ID.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /** The ISIN inside an {@code /aktien/…/<ISIN>} instrument link, else null. */
    static String instrumentIsin(String html) {
        if (html == null) return null;
        Matcher m = INSTRUMENT_ISIN.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    /** The {@code <link rel="next">} target of a paginated page, or {@code null}. */
    static String relNext(String html) {
        if (html == null) return null;
        Matcher m = Pattern.compile("<link\\s+rel=\"next\"\\s+href=\"([^\"]+)\"")
                .matcher(html);
        return m.find() ? decodeEntities(m.group(1)) : null;
    }

    /** The {@code <link rel="canonical">} target, or {@code null}. */
    static String canonical(String html) {
        if (html == null) return null;
        Matcher m = Pattern.compile("<link\\s+rel=\"canonical\"\\s+href=\"([^\"]+)\"")
                .matcher(html);
        return m.find() ? decodeEntities(m.group(1)) : null;
    }

    /**
     * Splits a document into the chunks that start at each {@code marker}
     * occurrence - the robust stand-in for balanced-tag matching against
     * boerse.de's deeply nested and irregularly closed row markup (the split
     * boundary is the NEXT row, so no regex has to find the right {@code </div>}).
     */
    static List<String> chunksAt(String html, String marker) {
        List<String> out = new ArrayList<>();
        if (html == null || html.isEmpty()) return out;
        int i = html.indexOf(marker);
        while (i >= 0) {
            int next = html.indexOf(marker, i + marker.length());
            out.add(next < 0 ? html.substring(i) : html.substring(i, next));
            i = next;
        }
        return out;
    }

    /** The {@code <td>…</td>} cells of one table row, tags stripped, in order. */
    static List<String> cells(String rowHtml) {
        List<String> out = new ArrayList<>();
        if (rowHtml == null) return out;
        Matcher m = Pattern.compile("(?is)<td\\b[^>]*>(.*?)</td>").matcher(rowHtml);
        while (m.find()) out.add(text(m.group(1)));
        return out;
    }

    /** Raw (still-tagged) {@code <td>} bodies - needed when a cell carries a link. */
    static List<String> rawCells(String rowHtml) {
        List<String> out = new ArrayList<>();
        if (rowHtml == null) return out;
        Matcher m = Pattern.compile("(?is)<td\\b[^>]*>(.*?)</td>").matcher(rowHtml);
        while (m.find()) out.add(m.group(1));
        return out;
    }
}
