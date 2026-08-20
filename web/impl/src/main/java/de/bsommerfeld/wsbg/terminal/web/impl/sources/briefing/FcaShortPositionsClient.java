package de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.facts.ShortInterest;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * UK net short positions - the FCA's own daily register (keyless, live-probed
 * 2026-08-11: 108.851 disclosure rows).
 *
 * <p>The house held disclosed short positions for GERMANY only (the
 * Bundesanzeiger register). For a UK issuer the dossier could report the
 * day's short VOLUME and never the disclosed POSITIONS - who is short, how
 * much, since when - which is the half a reader can actually weigh.
 *
 * <p>The file is an XLSX, and it is read WITHOUT a spreadsheet library: an
 * XLSX is a zip of XML, the sheet needs three of its parts, and pulling a
 * general-purpose office format parser into the build for one daily table
 * would cost more than it carries. The register's shape is fixed by the
 * regulator (holder, issuer, ISIN, percent, position date) and is asserted on
 * the header row rather than assumed - a reordered column would otherwise
 * silently swap two fields.
 *
 * <p>The register is a HISTORY, not a snapshot: a holder appears once per
 * change, and a closed position is a row of 0 %. What a reader means by "who
 * is short" is the LATEST row per holder above zero - which is what this
 * answers, with the closed ones counted rather than dropped in silence.
 */
@Singleton
public class FcaShortPositionsClient {

    private static final Logger LOG = LoggerFactory.getLogger(FcaShortPositionsClient.class);

    static final String URL =
            "https://www.fca.org.uk/publication/data/short-positions-daily-update.xlsx";

    /** The whole register is one file - fetched at most this often. */
    private static final long CACHE_TTL_MS = 6 * 60 * 60 * 1000L;

    /** The header the parser binds its columns to, in the regulator's own words. */
    private static final List<String> HEADER = List.of(
            "position holder", "name of share issuer", "isin",
            "net short position (%)", "position date");

    /** Excel's day zero: serial 1 is 1900-01-01, and the sheet counts 1900 as a leap year. */
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);

    private static final Pattern SHARED_STRING = Pattern.compile("<si>(.*?)</si>", Pattern.DOTALL);
    private static final Pattern TEXT = Pattern.compile("<t[^>]*>(.*?)</t>", Pattern.DOTALL);
    private static final Pattern ROW = Pattern.compile("<row[^>]*>(.*?)</row>", Pattern.DOTALL);
    /**
     * One cell as its ATTRIBUTES plus its value. Reading {@code r} and
     * {@code t} out of one fixed attribute order looks simpler and is wrong:
     * the optional type attribute then never binds, every shared-string cell
     * reads as a number, and the header row - which is nothing but shared
     * strings - fails to match, so the whole file parses to nothing without a
     * single error (measured on the first live run).
     */
    private static final Pattern CELL = Pattern.compile(
            "<c ([^>]*?)/?>(?:<v>(.*?)</v>)?", Pattern.DOTALL);
    private static final Pattern ATTR_REF = Pattern.compile("r=\"([A-Z]+)\\d+\"");
    private static final Pattern ATTR_TYPE = Pattern.compile("t=\"(\\w+)\"");

    /** The endpoint carries no wall - the direct path is what actually delivers. */
    static final FetchUtil[] MODES = {FetchUtil.DIRECT, FetchUtil.BROWSER};

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(60);

    /** ISIN → the register's rows for it; rebuilt with the file. */
    private volatile Map<String, List<Meldung>> byIsin = Map.of();
    private volatile long fetchedAtMs;
    private final Object loadLock = new Object();

    /** One disclosure row exactly as the register carries it. */
    record Meldung(String halter, String emittent, String isin, double prozent, LocalDate datum) {}

    @Inject
    public FcaShortPositionsClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * The disclosed net short positions for {@code isin} - the latest row per
     * holder that is still above zero, largest first. {@code null} when the
     * register could not be read; a PRESENT record with an empty list when the
     * register carries the issuer with nothing open, and equally when it does
     * not carry the issuer at all - the FCA publishes UK-supervised issuers,
     * and for anything else "no disclosure here" is simply not a statement.
     */
    public ShortInterest byIsin(String isin) {
        if (isin == null || isin.isBlank()) return null;
        Map<String, List<Meldung>> register = register();
        if (register == null) return null;
        String key = isin.strip().toUpperCase(Locale.ROOT);
        List<Meldung> rows = register.get(key);
        if (rows == null) {
            return new ShortInterest(key, 0, List.of(), java.time.Instant.now().getEpochSecond());
        }
        Map<String, Meldung> neueste = new LinkedHashMap<>();
        int geschlossen = 0;
        for (Meldung m : rows) {
            Meldung seated = neueste.get(m.halter());
            if (seated == null || (m.datum() != null && seated.datum() != null
                    && m.datum().isAfter(seated.datum()))) {
                neueste.put(m.halter(), m);
            }
        }
        List<ShortInterest.ShortPosition> offen = new ArrayList<>();
        double summe = 0;
        for (Meldung m : neueste.values()) {
            if (m.prozent() <= 0) {
                geschlossen++;
                continue;
            }
            offen.add(new ShortInterest.ShortPosition(m.halter(), m.prozent(),
                    m.datum() == null ? null : m.datum().toString()));
            summe += m.prozent();
        }
        offen.sort((a, b) -> Double.compare(b.percent(), a.percent()));
        LOG.info("[fca-shorts] {} → {} open position(s), {} closed, {}% disclosed in total",
                key, offen.size(), geschlossen, String.format(Locale.ROOT, "%.2f", summe));
        return new ShortInterest(key, summe, List.copyOf(offen),
                java.time.Instant.now().getEpochSecond());
    }

    private Map<String, List<Meldung>> register() {
        Map<String, List<Meldung>> hit = byIsin;
        if (!hit.isEmpty() && System.currentTimeMillis() - fetchedAtMs < CACHE_TTL_MS) return hit;
        synchronized (loadLock) {
            if (!byIsin.isEmpty() && System.currentTimeMillis() - fetchedAtMs < CACHE_TTL_MS) {
                return byIsin;
            }
            try {
                // The house fetcher's BINARY path on purpose: this source is a
                // zip archive, which does not survive being decoded as
                // characters - the bytes ride raw().
                WebResponse resp = fetcher.fetchBinary(URL,
                        Map.of("Accept", "*/*"), requestTimeout, MODES);
                if (resp.status() != 200 || !resp.isBinary()) {
                    LOG.warn("[fca-shorts] register answered status {}", resp.status());
                    return byIsin.isEmpty() ? null : byIsin;
                }
                Map<String, List<Meldung>> parsed = parse(resp.raw());
                if (parsed.isEmpty()) {
                    LOG.warn("[fca-shorts] the register file carried no readable row - "
                            + "format changed?");
                    return byIsin.isEmpty() ? null : byIsin;
                }
                byIsin = parsed;
                fetchedAtMs = System.currentTimeMillis();
                LOG.info("[fca-shorts] register loaded: {} issuer(s)", parsed.size());
                return parsed;
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                LOG.warn("[fca-shorts] register load failed: {}", e.getMessage());
                return byIsin.isEmpty() ? null : byIsin;
            }
        }
    }

    /**
     * The XLSX → rows by ISIN. Package-private for tests: the two zip members
     * that matter are the shared string table and the single worksheet.
     */
    static Map<String, List<Meldung>> parse(byte[] xlsx) throws Exception {
        String sharedXml = null;
        String sheetXml = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if ("xl/sharedStrings.xml".equals(name)) {
                    sharedXml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                } else if (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml")
                        && sheetXml == null) {
                    sheetXml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        if (sheetXml == null) return Map.of();
        List<String> shared = sharedStrings(sharedXml);
        Map<String, List<Meldung>> out = new LinkedHashMap<>();
        Matcher rows = ROW.matcher(sheetXml);
        Map<String, Integer> spalte = null;
        while (rows.find()) {
            Map<String, String> cells = cells(rows.group(1), shared);
            if (spalte == null) {
                spalte = header(cells);
                // No header, no parse: binding columns by position to a file
                // whose order could change would swap holder and issuer without
                // a single error.
                if (spalte == null) return Map.of();
                continue;
            }
            String isin = value(cells, spalte, 2);
            String prozent = value(cells, spalte, 3);
            if (isin == null || isin.isBlank() || prozent == null) continue;
            double pct;
            try {
                pct = Double.parseDouble(prozent.strip());
            } catch (NumberFormatException e) {
                continue;
            }
            out.computeIfAbsent(isin.strip().toUpperCase(Locale.ROOT), k -> new ArrayList<>())
                    .add(new Meldung(value(cells, spalte, 0), value(cells, spalte, 1),
                            isin.strip().toUpperCase(Locale.ROOT), pct,
                            excelDate(value(cells, spalte, 4))));
        }
        return out;
    }

    /** Column letter per expected header field, or null when the header does not match. */
    private static Map<String, Integer> header(Map<String, String> cells) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (var cell : cells.entrySet()) {
            String label = cell.getValue() == null ? ""
                    : cell.getValue().strip().toLowerCase(Locale.ROOT);
            int idx = HEADER.indexOf(label);
            if (idx >= 0) out.put(cell.getKey(), idx);
        }
        return out.size() == HEADER.size() ? out : null;
    }

    /** The value of the field at {@code index} of {@link #HEADER}, or null. */
    private static String value(Map<String, String> cells, Map<String, Integer> spalte,
            int index) {
        for (var e : spalte.entrySet()) {
            if (e.getValue() == index) return cells.get(e.getKey());
        }
        return null;
    }

    /** One row's cells by column letter, shared strings already resolved. */
    private static Map<String, String> cells(String rowXml, List<String> shared) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = CELL.matcher(rowXml);
        while (m.find()) {
            String attrs = m.group(1);
            Matcher ref = ATTR_REF.matcher(attrs);
            if (!ref.find()) continue;
            String column = ref.group(1);
            Matcher typeMatch = ATTR_TYPE.matcher(attrs);
            String type = typeMatch.find() ? typeMatch.group(1) : null;
            String raw = m.group(2);
            if (raw == null) continue;
            if ("s".equals(type)) {
                try {
                    int i = Integer.parseInt(raw.strip());
                    out.put(column, i >= 0 && i < shared.size() ? shared.get(i) : null);
                } catch (NumberFormatException e) {
                    out.put(column, null);
                }
            } else {
                out.put(column, raw);
            }
        }
        return out;
    }

    /** The workbook's shared string table, in index order. */
    private static List<String> sharedStrings(String xml) {
        List<String> out = new ArrayList<>();
        if (xml == null) return out;
        Matcher si = SHARED_STRING.matcher(xml);
        while (si.find()) {
            // A single string may be split into several runs; the value is
            // their concatenation, not the first of them.
            StringBuilder text = new StringBuilder();
            Matcher t = TEXT.matcher(si.group(1));
            while (t.find()) text.append(unescape(t.group(1)));
            out.add(text.toString());
        }
        return out;
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
    }

    /** An Excel day serial → the date it means; null when it is not a serial. */
    static LocalDate excelDate(String serial) {
        if (serial == null || serial.isBlank()) return null;
        try {
            double days = Double.parseDouble(serial.strip());
            if (days <= 0) return null;
            return EXCEL_EPOCH.plusDays((long) days);
        } catch (NumberFormatException e) {
            try {
                return LocalDate.parse(serial.strip());
            } catch (Exception notADate) {
                return null;
            }
        }
    }
}
