package de.bsommerfeld.wsbg.terminal.web.impl.sources.websearch;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The world twin's archive-window leg against a fake fetcher: one request per
 * language GROUP, each carrying the same {@code startdatetime}/
 * {@code enddatetime} window, with the non-Latin escape of the referent
 * filter (a Han headline writes the company in its own characters).
 */
class GdeltWorldSourceTest {

    /** One Han-script article (kept by the non-Latin escape), one Latin off-topic one. */
    private static final String FIXTURE = """
            {"articles":[
              {"title":"莱茵金属获得大订单","url":"https://ex.cn/a","domain":"ex.cn",
               "seendate":"20200315T120000Z","language":"Chinese","sourcecountry":"China"},
              {"title":"Defense stocks rally","url":"https://ex.com/b","domain":"ex.com",
               "seendate":"20200316T090000Z","language":"English","sourcecountry":"United States"}
            ]}""";

    private static WebFetcher fake(List<String> asked) {
        return new WebFetcher() {
            @Override
            public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                    FetchUtil... modes) {
                asked.add(url);
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
    void archiveWindowFansTheLanguageGroupsWithGdeltStamps() throws Exception {
        List<String> asked = new ArrayList<>();
        GdeltWorldSource source = new GdeltWorldSource(fake(asked));

        List<Article> items = source.newsForWindow(
                ResolvedInstrument.ofName("Rheinmetall AG"),
                LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1), 10);

        assertEquals(2, asked.size(), "one request per language group");
        for (String url : asked) {
            assertTrue(url.contains("%22Rheinmetall%22"), url);
            assertTrue(url.contains("sourcelang%3A"), "the group's language pin rides: " + url);
            assertTrue(url.contains("&startdatetime=20200101000000&enddatetime=20210101000000"),
                    url);
        }
        // The Han title survives the referent filter (non-Latin escape), the
        // Latin off-topic one does not; the second group's duplicate links
        // collapse in the cross-group dedup.
        assertEquals(1, items.size(), String.valueOf(items));
        assertEquals("莱茵金属获得大订单", items.get(0).title());
        assertTrue(items.get(0).origin().known(),
                "GDELT's own language/sourcecountry fields stamp the origin");
    }

    @Test
    void windowsBeforeTheIndexStartStaySilentWithoutARequest() throws Exception {
        List<String> asked = new ArrayList<>();
        GdeltWorldSource source = new GdeltWorldSource(fake(asked));

        List<Article> items = source.newsForWindow(ResolvedInstrument.ofName("Rheinmetall AG"),
                LocalDate.of(2015, 1, 1), LocalDate.of(2016, 1, 1), 10);

        assertTrue(items.isEmpty());
        assertTrue(asked.isEmpty(), "an unanswerable window is never sent to the host");
    }
}
