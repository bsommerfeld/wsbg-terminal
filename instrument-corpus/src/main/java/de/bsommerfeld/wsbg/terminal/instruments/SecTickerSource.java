package de.bsommerfeld.wsbg.terminal.instruments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The SEC's official company↔ticker file — every US-listed registrant, updated
 * daily, keyless: {@code https://www.sec.gov/files/company_tickers.json}, shape
 * {@code {"0":{"cik_str":320193,"ticker":"AAPL","title":"Apple Inc."}, …}}
 * (live-verified 2026-07-03). The SEC asks for a descriptive User-Agent with a
 * contact address; anonymous UAs get throttled.
 */
public final class SecTickerSource implements CorpusSource {

    static final String URL = "https://www.sec.gov/files/company_tickers.json";
    private static final String USER_AGENT = "wsbg-terminal instrument-corpus (contact: b.sommerfeld2003@gmail.com)";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebFetcher fetcher;

    public SecTickerSource(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String name() {
        return "sec";
    }

    @Override
    public List<InstrumentEntry> fetch() throws Exception {
        WebResponse resp = fetcher.fetch(URL, Map.of(
                "User-Agent", USER_AGENT, "Accept", "application/json"), Duration.ofSeconds(30));
        if (resp.status() != 200) {
            throw new IllegalStateException("SEC company_tickers returned HTTP " + resp.status());
        }
        return parse(resp.body());
    }

    /** Package-private for testing. */
    static List<InstrumentEntry> parse(String body) throws Exception {
        JsonNode root = JSON.readTree(body);
        List<InstrumentEntry> out = new ArrayList<>(root.size());
        for (JsonNode e : root) {
            String ticker = e.path("ticker").asText("").trim();
            String title = e.path("title").asText("").trim();
            if (ticker.isEmpty() || title.isEmpty()) continue;
            out.add(new InstrumentEntry(ticker, title, null, "US", "EQUITY", "sec"));
        }
        return out;
    }
}
