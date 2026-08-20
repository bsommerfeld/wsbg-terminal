package de.bsommerfeld.wsbg.terminal.web.impl.catalog;

import de.bsommerfeld.wsbg.terminal.web.article.SourceOrigin;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.schedule.FetchInterval;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads the curated source list — {@code /web/sources.csv} on the classpath.
 * One row, one feed source; the file replaces a class-and-module per source
 * for every feed of a known shape.
 *
 * <p>Format (semicolon-separated, {@code #} comments, blank lines ignored):
 * <pre>
 * name;publisher;lang;sphere;modes;min;max;social;accept;url[|url…]
 * </pre>
 * {@code modes} is the transport order as {@code DIRECT}, {@code BROWSER} or
 * {@code DIRECT,BROWSER} (order = fallback order). {@code min}/{@code max}
 * are the collector interval bounds in minutes. A malformed row is a HARD
 * error at load — a silently dropped source register entry does not exist
 * for planning, so the file must always parse whole.
 */
public final class SourceCatalog {

    static final String RESOURCE = "/web/sources.csv";

    private SourceCatalog() {
    }

    /** Loads the shipped catalog. */
    public static List<CatalogRow> load() {
        try (InputStream in = SourceCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("catalog missing: " + RESOURCE);
            return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("catalog unreadable: " + RESOURCE, e);
        }
    }

    static List<CatalogRow> parse(BufferedReader reader) throws Exception {
        List<CatalogRow> rows = new ArrayList<>();
        Set<String> names = new HashSet<>();
        String line;
        int no = 0;
        while ((line = reader.readLine()) != null) {
            no++;
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#")) continue;
            String[] f = s.split(";", -1);
            if (f.length != 10) {
                throw new IllegalStateException(
                        "catalog line " + no + ": expected 10 fields, got " + f.length);
            }
            String name = f[0].strip();
            if (name.isEmpty() || !names.add(name)) {
                throw new IllegalStateException("catalog line " + no + ": blank/duplicate name");
            }
            List<FetchUtil> modes = parseModes(f[4], no);
            FetchInterval interval;
            try {
                interval = FetchInterval.of(Integer.parseInt(f[5].strip()),
                        Integer.parseInt(f[6].strip()));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("catalog line " + no + ": " + e.getMessage());
            }
            List<String> urls = new ArrayList<>();
            for (String u : f[9].split("\\|")) {
                String url = u.strip();
                if (!url.startsWith("http")) {
                    throw new IllegalStateException("catalog line " + no + ": bad url '" + url + "'");
                }
                urls.add(url);
            }
            if (urls.isEmpty()) {
                throw new IllegalStateException("catalog line " + no + ": no urls");
            }
            rows.add(new CatalogRow(
                    name,
                    f[1].strip(),
                    SourceOrigin.of(f[2].strip(), f[3].strip()),
                    modes,
                    interval,
                    Boolean.parseBoolean(f[7].strip()),
                    f[8].strip().isEmpty()
                            ? "application/rss+xml, application/xml, text/xml"
                            : f[8].strip(),
                    List.copyOf(urls)));
        }
        return List.copyOf(rows);
    }

    private static List<FetchUtil> parseModes(String field, int lineNo) {
        List<FetchUtil> modes = new ArrayList<>();
        for (String m : field.split(",")) {
            try {
                FetchUtil mode = FetchUtil.valueOf(m.strip().toUpperCase(Locale.ROOT));
                if (!modes.contains(mode)) modes.add(mode);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "catalog line " + lineNo + ": unknown mode '" + m.strip() + "'");
            }
        }
        if (modes.isEmpty()) {
            throw new IllegalStateException("catalog line " + lineNo + ": no modes");
        }
        return List.copyOf(modes);
    }
}
