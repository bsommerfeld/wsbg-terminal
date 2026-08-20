package de.bsommerfeld.wsbg.terminal.web.impl.sources.websearch;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The archive-window leg against a fake fetcher: the query the old world
 * addressed this backend with exclusively - quoted name, German pin,
 * {@code startdatetime}/{@code enddatetime} in GDELT's own stamp format,
 * referent-filtered on the leading name word in the TITLE.
 */
class GdeltDocSourceTest {

    /** Two articles: one names Mutares in the title, one only in the body (bycatch). */
    private static final String FIXTURE = """
            {"articles":[
              {"title":"Mutares kauft erneut zu","url":"https://ex.de/a",
               "domain":"ex.de","seendate":"20200315T120000Z"},
              {"title":"Beteiligungsfirmen im Vergleich","url":"https://ex.de/b",
               "domain":"ex.de","seendate":"20200316T090000Z"}
            ]}""";

    private static WebFetcher fake(AtomicInteger fetches, AtomicReference<String> asked) {
        return new WebFetcher() {
            @Override
            public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                    FetchUtil... modes) {
                fetches.incrementAndGet();
                asked.set(url);
                assertEquals(FetchUtil.DIRECT, modes[0], "declared mode order rides the fetch");
                return WebResponse.text(200, FIXTURE, Map.of());
            }

            @Override
            public WebResponse fetchBinary(String url, Map<String, String> headers,
                    Duration timeout, FetchUtil... modes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public WebResponse post(String url, Map<String, String> headers, String body,
                    String contentType, Duration timeout, FetchUtil... modes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean hostCoolingDown(String url) {
                return false;
            }
        };
    }

    @Test
    void archiveWindowBuildsGdeltStampsAndFiltersOnTheTitle() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        AtomicReference<String> asked = new AtomicReference<>();
        GdeltDocSource source = new GdeltDocSource(fake(fetches, asked));

        List<Article> items = source.newsForWindow(
                ResolvedInstrument.ofName("Mutares SE & Co. KGaA"),
                LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1), 10);

        String url = asked.get();
        // Quoted, legal-tail-cleaned name + the German pin, %20-encoded (never '+').
        assertTrue(url.contains("query=%22Mutares%22%20sourcelang%3Agerman"), url);
        // GDELT wants YYYYMMDDHHMMSS - no T/Z, no dashes.
        assertTrue(url.contains("&startdatetime=20200101000000&enddatetime=20210101000000"),
                url);
        assertEquals(1, items.size(),
                "the body-only mention is bycatch for a per-name history");
        assertEquals("Mutares kauft erneut zu", items.get(0).title());
        assertEquals(Instant.parse("2020-03-15T12:00:00Z"), items.get(0).publishedAt());
    }

    @Test
    void windowsBeforeTheIndexStartStaySilentWithoutARequest() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        GdeltDocSource source = new GdeltDocSource(fake(fetches, new AtomicReference<>()));

        // GDELT's index starts January 2017 - the window ends exactly there.
        List<Article> items = source.newsForWindow(ResolvedInstrument.ofName("Mutares SE"),
                LocalDate.of(2016, 1, 1), LocalDate.of(2017, 1, 1), 10);

        assertTrue(items.isEmpty());
        assertEquals(0, fetches.get(), "an unanswerable window is never sent to the host");
    }
}
