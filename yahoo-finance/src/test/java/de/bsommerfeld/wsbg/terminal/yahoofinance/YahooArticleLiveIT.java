package de.bsommerfeld.wsbg.terminal.yahoofinance;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Live check that {@link YahooFinanceClient#fetchArticleText} actually yields
 * readable body text from a real news link (redirect-follow + extraction).
 * Capability is standalone (not wired into the pipeline); this just proves it.
 *
 * <pre>YAHOO_ARTICLE_LIVE=true mvn test -pl yahoo-finance -Dtest=YahooArticleLiveIT -Dtest.excludedGroups=</pre>
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "YAHOO_ARTICLE_LIVE", matches = "true")
class YahooArticleLiveIT {

    @Test
    void fetchesReadableBodyFromARealNewsLink() {
        YahooFinanceClient client = new YahooFinanceClient(12, 300);
        List<YahooNewsItem> news = client.getNewsForSymbol("NVDA", 5);
        System.out.println("[ARTICLE-IT] news items: " + news.size());

        int hits = 0;
        for (YahooNewsItem n : news) {
            Optional<String> body = client.fetchArticleText(n.link());
            String preview = body.map(t -> t.substring(0, Math.min(220, t.length()))).orElse("(none)");
            System.out.println("\n[ARTICLE-IT] " + n.title());
            System.out.println("   link: " + n.link());
            System.out.println("   len : " + body.map(String::length).orElse(0));
            System.out.println("   text: " + preview.replaceAll("\\s+", " "));
            if (body.isPresent() && body.get().length() > 400) hits++;
        }
        assertFalse(news.isEmpty(), "expected some NVDA news to test against");
        System.out.println("\n[ARTICLE-IT] articles with usable body (>400 chars): " + hits + "/" + news.size());
    }
}
