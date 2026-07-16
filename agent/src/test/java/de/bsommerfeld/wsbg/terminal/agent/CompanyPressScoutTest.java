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

    /**
     * The IR-archive mode (2026-07-16): report-shaped entries with their
     * dates, nav noise and non-filing links skipped, dates from anchor text
     * or href - ISO first, dotted German, bare year as the honest fallback.
     */
    @Test
    void extractIrEntriesLiftsDatedReports() {
        String html = """
                <a href="/investor/q1-2026.pdf">Quartalsmitteilung Q1 2026</a>
                <a href="/investor/hv">Hauptversammlung am 14.05.2026</a>
                <a href="/investor/report-2024">Annual Report 2024</a>
                <a href="/karriere">Karriere bei der Example AG - jetzt bewerben</a>
                <a href="/investor/glossar">Unser Glossar erklärt alle Begriffe im Detail</a>""";
        List<CompanyPressScout.IrEntry> out =
                CompanyPressScout.extractIrEntries(html, HOME, 10);
        assertEquals(3, out.size(), String.valueOf(out));
        assertEquals("2026", out.get(0).dateIso());
        assertEquals("2026-05-14", out.get(1).dateIso());
        assertEquals("Annual Report 2024", out.get(2).title());
        assertEquals("2024", out.get(2).dateIso());
    }

    @Test
    void irDateParsesIsoDottedAndBareYear() {
        assertEquals("2026-07-24", CompanyPressScout.irDate("Report", "/x/2026-07-24/report"));
        assertEquals("2026-05-14", CompanyPressScout.irDate("HV am 14.5.2026", "/hv"));
        assertEquals("2024", CompanyPressScout.irDate("Annual Report 2024", "/r"));
        assertNull(CompanyPressScout.irDate("Interim statement", "/statement"));
    }

    @Test
    void normalizeToleratesSchemelessProfileUrls() {
        assertEquals("www.sap.com", CompanyPressScout.normalize("www.sap.com").getHost());
        assertEquals("www.sap.com", CompanyPressScout.normalize("https://www.sap.com").getHost());
        assertNull(CompanyPressScout.normalize("nicht eine url"));
    }
}
