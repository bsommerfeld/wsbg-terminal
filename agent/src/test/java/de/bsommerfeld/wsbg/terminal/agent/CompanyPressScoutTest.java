package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first-party press scout's deterministic HTML heuristics: finding the
 * press/IR section on a corporate homepage, lifting headline-shaped links from
 * a listing page, and staying on the company's own host family.
 */
class CompanyPressScoutTest {

    private static final URI HOME = URI.create("https://www.example-ag.de/");

    @Test
    void findsTheMostPressLikeLink() {
        String html = """
                <nav>
                  <a href="/karriere">Karriere</a>
                  <a href="/news">News</a>
                  <a href="/presse/mitteilungen">Pressemitteilungen</a>
                  <a href="https://evil.example.org/presse">Fremde Presse</a>
                </nav>""";
        String best = CompanyPressScout.bestPressLink(html, HOME);
        assertEquals("https://www.example-ag.de/presse/mitteilungen", best,
                "the specific press-release link outranks the generic news link");
    }

    @Test
    void subdomainOfTheCompanyCounts() {
        String html = "<a href=\"https://news.example-ag.de/pressemitteilungen\">Presse</a>";
        assertEquals("https://news.example-ag.de/pressemitteilungen",
                CompanyPressScout.bestPressLink(html, HOME));
        assertTrue(CompanyPressScout.sameHostFamily(HOME, "https://news.example-ag.de/x"));
        assertFalse(CompanyPressScout.sameHostFamily(HOME, "https://example-ag.de.evil.org/x"));
    }

    @Test
    void liftsHeadlineShapedLinksAndSkipsNavNoise() {
        String html = """
                <a href="/presse/2026/umbau">Example AG ordnet Konzernstruktur neu und
                  bündelt die Cloud-Sparte</a>
                <a href="/presse/2026/quartal">Example AG legt Zahlen zum zweiten Quartal 2026 vor</a>
                <a href="/kontakt">Kontaktieren Sie unser Team für weitere Informationen</a>
                <a href="/presse/2026/umbau">Example AG ordnet Konzernstruktur neu und
                  bündelt die Cloud-Sparte</a>
                <a href="/x">kurz</a>
                <a href="https://other.org/story">Fremder Artikel über die Example AG und ihre Zukunft</a>""";
        List<CompanyPressScout.Headline> out = CompanyPressScout.extractHeadlines(html, HOME);
        assertEquals(2, out.size(), String.valueOf(out));
        assertTrue(out.get(0).title().startsWith("Example AG ordnet Konzernstruktur"));
        assertEquals("https://www.example-ag.de/presse/2026/quartal", out.get(1).url());
    }

    @Test
    void normalizeToleratesSchemelessProfileUrls() {
        assertEquals("www.sap.com", CompanyPressScout.normalize("www.sap.com").getHost());
        assertEquals("www.sap.com", CompanyPressScout.normalize("https://www.sap.com").getHost());
        assertNull(CompanyPressScout.normalize("nicht eine url"));
    }
}
