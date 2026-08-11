package de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.SourceTestSupport.FakeWebFetcher;
import de.bsommerfeld.wsbg.terminal.web.instrument.Isin;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Window fan of the EQS disclosure archive: ISIN-addressed page walk, records
 * cut to the window, categorized headline as the title, no article URL.
 */
class EqsNewsArchiveClientTest {

    private static final String PAGE_1 = """
            {"records":[
              {"id":"901","headline":"Kapitalerhöhung beschlossen","category":"Ad-hoc",
               "dateUtc":"2026-08-05 07:00:00"},
              {"id":"902","headline":"Mutares veräußert Segment","category":"Ad-hoc",
               "dateUtc":"2026-07-16 10:00:00"},
              {"id":"903","headline":"Directors' Dealings","category":"",
               "dateUtc":"2026-06-01 09:30:00"}
            ]}""";

    /** Every record older than the window - the walk must end here. */
    private static final String PAGE_2 = """
            {"records":[
              {"id":"904","headline":"Jahresabschluss 2025","category":"Corporate News",
               "dateUtc":"2026-05-01 08:00:00"}
            ]}""";

    private static ResolvedInstrument byIsin(String isin) {
        return new ResolvedInstrument(Isin.parse(isin), Optional.empty(), "Mutares");
    }

    @Test
    void windowCutsRecordsAndStopsOnAPageEntirelyOlderThanTheWindow() throws Exception {
        FakeWebFetcher fetcher = new FakeWebFetcher()
                .on("isin=DE000A2NB650&page=1", PAGE_1)
                .on("isin=DE000A2NB650&page=2", PAGE_2);
        EqsNewsArchiveClient client = new EqsNewsArchiveClient(fetcher);

        List<Article> items = client.newsForWindow(byIsin("DE000A2NB650"),
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-08-01"), 20);

        assertEquals(1, items.size(), "only the July record sits inside the window");
        Article it = items.get(0);
        assertEquals("[Ad-hoc] Mutares veräußert Segment", it.title(),
                "the category rides the headline as a prefix");
        assertEquals("eqs-902", it.uuid());
        assertEquals("EQS-News", it.publisher());
        assertNull(it.link(), "EQS records carry no article URL");
        assertEquals(Instant.parse("2026-07-16T10:00:00Z"), it.publishedAt());

        assertEquals(1, fetcher.count("page=1"));
        assertEquals(1, fetcher.count("page=2"),
                "page 1 still carries a record at/after the floor, so page 2 is probed");
        assertEquals(0, fetcher.count("page=3"),
                "a page entirely older than the window ends the newest-first walk");
    }

    @Test
    void withoutAnIsinTheArchiveStaysSilent() throws Exception {
        FakeWebFetcher fetcher = new FakeWebFetcher();
        EqsNewsArchiveClient client = new EqsNewsArchiveClient(fetcher);

        assertTrue(client.newsForWindow(ResolvedInstrument.ofName("Mutares"),
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-08-01"), 10).isEmpty(),
                "ISIN-addressed only - a name is never a key here");
        assertTrue(client.newsForWindow(byIsin("DE000A2NB650"),
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-08-01"), 0).isEmpty());
        assertEquals(0, fetcher.total());
    }
}
