package de.bsommerfeld.wsbg.terminal.wallstreetonline;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing of wallstreet-online's {@code searchNews} reply — fixture taken from a
 * live response (2026-07-01). The date rides inside the label HTML: a
 * {@code previous-day} span for older items, a bare {@code HH:mm:ss} for today's.
 */
class WsoNewsClientTest {

    // Live-shaped fixture: one dated item, one today-item, one off-topic item
    // (title never names the company → must be filtered).
    private static final String FIXTURE = """
            {"status":1,"result":[
              {"label":"<a href=\\"/nachricht/1\\"><span class=\\"fadeBox\\">Nach Kampfjet-Aus jetzt Kampfpanzer-Aus? <b>Rheinmetall</b>-Chef mahnt</span><span class=\\"wknBox\\"><span class=\\"previous-day\\">15.06.26</span></span></a>",
               "category":"News","link":"/nachricht/1","title":"Nach Kampfjet-Aus jetzt Kampfpanzer-Aus? Rheinmetall-Chef mahnt","id":20999468},
              {"label":"<a href=\\"/nachricht/2\\"><span class=\\"fadeBox\\"><b>Rheinmetall</b> Aktie legt zu</span><span class=\\"wknBox\\">09:58:00</span></a>",
               "category":"News","link":"/nachricht/2","title":"Rheinmetall Aktie legt zu - 01.07.2026","id":21061200},
              {"label":"<a href=\\"/nachricht/3\\"><span class=\\"fadeBox\\">Wolfram besser als Gold!</span><span class=\\"wknBox\\"><span class=\\"previous-day\\">29.06.26</span></span></a>",
               "category":"News","link":"/nachricht/3","title":"Wolfram besser als Gold!","id":21049803}
            ]}""";

    @Test
    void parseKeepsTitleRelevantItemsWithDatesAndAbsoluteLinks() throws Exception {
        List<RawNewsItem> items = WsoNewsClient.parse(FIXTURE, "Rheinmetall AG", 10);
        assertEquals(2, items.size(), "the off-topic roundup (no 'Rheinmetall' in title) is filtered");

        RawNewsItem dated = items.get(0);
        assertEquals("wso-20999468", dated.uuid());
        assertEquals("wallstreet-online", dated.publisher());
        assertTrue(dated.link().startsWith("https://www.wallstreet-online.de/nachricht/"));
        assertEquals(ZonedDateTime.of(2026, 6, 15, 0, 0, 0, 0, ZoneId.of("Europe/Berlin")).toInstant(),
                dated.publishedAt(), "previous-day dd.MM.yy parses to that Berlin day");

        RawNewsItem today = items.get(1);
        assertNotNull(today.publishedAt(), "a bare HH:mm:ss means today");
    }

    @Test
    void publishedAtToleratesUnparseableLabels() {
        assertNull(WsoNewsClient.publishedAt("<span class=\"wknBox\">gestern</span>"));
        assertNull(WsoNewsClient.publishedAt(""));
    }

    @Test
    void queryCandidatesCleanLegalSuffixesFirst() {
        assertEquals(List.of("NVIDIA", "NVIDIA Corporation"),
                WsoNewsClient.queryCandidates("NVIDIA Corporation"));
        assertEquals(List.of("Amazon.com", "Amazon.com, Inc."),
                WsoNewsClient.queryCandidates("Amazon.com, Inc."));
        assertEquals(List.of("Meta Wolf", "Meta Wolf AG"),
                WsoNewsClient.queryCandidates("Meta Wolf AG"));
        assertEquals(List.of("Rheinmetall"), WsoNewsClient.queryCandidates("Rheinmetall"));
    }

    @Test
    void titleMatchingIsWordLevelAndUmlautTolerant() {
        var words = WsoNewsClient.significantWords("Münchener Rück");
        assertTrue(WsoNewsClient.titleMatches("Muenchener Rueck hebt Dividende an", words));
        assertFalse(WsoNewsClient.titleMatches("Allianz hebt Dividende an", words));
    }
}
