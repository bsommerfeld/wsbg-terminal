package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clusterer decides what counts as ONE story. Both directions are tested,
 * because both cost: a wrong merge hides a fact, a wrong split spends a news
 * slot and a digest call on a duplicate.
 */
class StoryClustererTest {

    private static Article a(String uuid, String publisher, String title) {
        return new Article(uuid, title, publisher, "https://x.example/" + uuid,
                Instant.parse("2026-08-12T10:00:00Z"), List.of(), null, null, false, null);
    }

    @Test
    void threeOutletsOnOneReportBecomeOneItem() {
        List<Article> folded = StoryClusterer.fold(List.of(
                a("r1", "Reuters", "Siemens hebt Jahresprognose nach starkem Quartal an"),
                a("b1", "Bloomberg", "Siemens hebt die Jahresprognose nach starkem Quartal an"),
                a("d1", "dpa-AFX", "Siemens hebt Jahresprognose nach einem starken Quartal an")));

        assertEquals(1, folded.size(), "one report, one item");
        assertTrue(folded.get(0).publisher().startsWith("Reuters"),
                "the first (freshest) telling survives");
        assertTrue(folded.get(0).publisher().contains("Bloomberg"),
                "how widely a story is carried is signal and must not be lost");
        assertTrue(folded.get(0).publisher().contains("dpa-AFX"));
    }

    @Test
    void differentStoriesAboutOneCompanyStayApart() {
        List<Article> folded = StoryClusterer.fold(List.of(
                a("1", "Reuters", "Siemens hebt Jahresprognose nach starkem Quartal an"),
                a("2", "Reuters", "Siemens verkauft Antriebssparte an Finanzinvestor")));

        assertEquals(2, folded.size(), "a raised outlook is not a divestment");
    }

    @Test
    void shortTitlesFoldOnlyOnAnExactMatch() {
        // "Quartalszahlen" vs "Quartalszahlen erwartet" is not evidence of
        // anything — too few tokens to judge overlap on.
        List<Article> folded = StoryClusterer.fold(List.of(
                a("1", "A", "Quartalszahlen Siemens"),
                a("2", "B", "Quartalszahlen Siemens erwartet")));
        assertEquals(2, folded.size());

        List<Article> exact = StoryClusterer.fold(List.of(
                a("1", "A", "Quartalszahlen Siemens"),
                a("2", "B", "Quartalszahlen Siemens!")));
        assertEquals(1, exact.size(), "normalised-identical titles fold whatever their length");
    }

    @Test
    void theByLineCountsTheTailInsteadOfListingIt() {
        String title = "Bundesbank senkt Wachstumsprognose fuer das laufende Jahr deutlich";
        List<Article> folded = StoryClusterer.fold(List.of(
                a("1", "Reuters", title),
                a("2", "Bloomberg", title),
                a("3", "dpa-AFX", title),
                a("4", "Handelsblatt", title),
                a("5", "n-tv", title)));

        assertEquals(1, folded.size());
        assertEquals("Reuters · auch: Bloomberg, dpa-AFX +2", folded.get(0).publisher());
    }

    @Test
    void ordersAndSingletonsAreLeftAlone() {
        List<Article> one = List.of(a("1", "Reuters", "Etwas ist geschehen heute Morgen"));
        assertEquals(one, StoryClusterer.fold(one));
        assertEquals(List.of(), StoryClusterer.fold(null));
    }
}
