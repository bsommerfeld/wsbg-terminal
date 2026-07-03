package de.bsommerfeld.wsbg.terminal.instruments;

import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deutsche Börse's official "All Tradable Instruments" list for XETRA — every
 * tradable stock and ETF with name, ISIN, WKN and Mnemonic, updated daily. The
 * CSV itself hides behind a blob URL whose hash CHANGES per publication, so this
 * source goes two steps: fetch the stable instruments page, regex the current
 * {@code t7-xetr-allTradableInstruments.csv} href out of it, then fetch that.
 * (Live-mapped 2026-07-04; the old "unstable blob URL" blocker dissolves once
 * the page itself is the entry point.)
 */
public final class XetraSource implements CorpusSource {

    private static final Logger LOG = LoggerFactory.getLogger(XetraSource.class);

    static final String PAGE_URL = "https://www.xetra.com/xetra-en/instruments/instruments";
    private static final String BASE = "https://www.xetra.com";
    private static final Pattern CSV_HREF =
            Pattern.compile("href=\"([^\"]*allTradableInstruments\\.csv)\"", Pattern.CASE_INSENSITIVE);
    private static final String USER_AGENT =
            "wsbg-terminal instrument-corpus (contact: b.sommerfeld2003@gmail.com)";

    private final WebFetcher fetcher;

    public XetraSource(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String name() {
        return "xetra";
    }

    @Override
    public List<InstrumentEntry> fetch() throws Exception {
        WebResponse page = fetcher.fetch(PAGE_URL, Map.of("User-Agent", USER_AGENT), Duration.ofSeconds(30));
        if (page.status() != 200) {
            throw new IllegalStateException("XETRA instruments page returned HTTP " + page.status());
        }
        Matcher m = CSV_HREF.matcher(page.body());
        if (!m.find()) {
            throw new IllegalStateException("XETRA instruments page carries no allTradableInstruments.csv link");
        }
        String url = m.group(1).startsWith("http") ? m.group(1) : BASE + m.group(1);
        WebResponse csv = fetcher.fetch(url, Map.of("User-Agent", USER_AGENT), Duration.ofSeconds(60));
        if (csv.status() != 200) {
            throw new IllegalStateException("XETRA instruments CSV returned HTTP " + csv.status());
        }
        List<InstrumentEntry> out = parse(csv.body());
        LOG.debug("[CORPUS] xetra CSV at {} → {} instruments", url, out.size());
        return out;
    }

    /**
     * Semicolon-CSV, two metadata lines + a header row, then one instrument per
     * line: col 3 = name, 4 = ISIN, 8 = Mnemonic (the Börsenkürzel — Yahoo's
     * XETRA symbols are {@code <Mnemonic>.DE}), 19 = Instrument Type. Only Active
     * stocks (CS) and ETFs are taken; ETC/ETN trackers stay out (the commodity
     * catalog covers raw materials with the real futures). Package-private for
     * testing.
     */
    static List<InstrumentEntry> parse(String body) {
        List<InstrumentEntry> out = new ArrayList<>(5000);
        boolean headerSeen = false;
        for (String line : body.split("\r?\n")) {
            if (!headerSeen) {
                if (line.startsWith("Product Status;")) headerSeen = true;
                continue;
            }
            String[] c = line.split(";", -1);
            if (c.length < 19) continue;
            String status = c[0].trim();
            String name = c[2].trim();
            String isin = c[3].trim();
            String mnemonic = c[7].trim();
            String type = c[18].trim().toUpperCase(Locale.ROOT);
            if (!"Active".equalsIgnoreCase(status) || name.isEmpty() || mnemonic.isEmpty()) continue;
            String mapped = switch (type) {
                case "CS" -> "EQUITY";
                case "ETF" -> "ETF";
                default -> null; // ETC/ETN/SR — skip
            };
            if (mapped == null) continue;
            out.add(new InstrumentEntry(mnemonic + ".DE", name,
                    isin.isEmpty() ? null : isin, "XETRA", mapped, "xetra"));
        }
        return out;
    }
}
