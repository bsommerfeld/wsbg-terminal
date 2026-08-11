package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.impl.net.DirectTransport;
import de.bsommerfeld.wsbg.terminal.web.impl.net.HouseFetcher;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The live walk: real HTTP against real corporate sites, no fixtures. Proves
 * the frontier reaches an actual newsroom and an actual report archive and
 * that the budgets hold on pages nobody wrote for us.
 *
 * <pre>SITE_CRAWL_SMOKE=true mvn test -pl agent -Dtest=CompanySiteCrawlerIT -Dtest.excludedGroups=</pre>
 *
 * <p>{@code SITE_CRAWL_SITES} overrides the probed sites (comma-separated).
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "SITE_CRAWL_SMOKE", matches = "true")
class CompanySiteCrawlerIT {

    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    @Test
    void walksRealCorporateSites() {
        String sites = System.getenv().getOrDefault("SITE_CRAWL_SITES",
                "www.sap.com,www.siemens.com,www.evotec.com");
        CompanySiteCrawler crawler = new CompanySiteCrawler(
                new HouseFetcher(java.util.Set.of(new DirectTransport())), UA);
        CompanyPressScout scout = new CompanyPressScout(crawler);
        int totalItems = 0;
        for (String site : sites.split(",")) {
            long t0 = System.currentTimeMillis();
            CompanySiteCrawler.Crawl crawl = crawler.crawl(site.strip());
            long ms = System.currentTimeMillis() - t0;
            List<Article> press = scout.pressItems(crawl, 6);
            List<CompanyPressScout.IrEntry> ir = scout.irEntries(crawl, 12);
            totalItems += press.size() + ir.size();
            System.out.printf("%n== %s: %d page(s), %d feed item(s) in %d ms%n",
                    site, crawl.pages().size(), crawl.feedItems().size(), ms);
            for (CompanySiteCrawler.Page p : crawl.pages()) {
                System.out.printf("   [d%d p%-3d i%-3d] %s%n",
                        p.depth(), p.pressScore(), p.irScore(), p.url());
            }
            System.out.println("   -- press --");
            for (Article n : press) System.out.println("   * " + n.title());
            System.out.println("   -- IR --");
            for (CompanyPressScout.IrEntry e : ir) {
                System.out.println("   * " + e.dateIso() + "  " + e.title());
            }
        }
        assertFalse(totalItems == 0, "not one first-party item off any probed site");
    }
}
