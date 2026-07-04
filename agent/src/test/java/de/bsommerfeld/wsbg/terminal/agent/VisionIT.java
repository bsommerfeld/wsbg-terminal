package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real vision-model coverage over STATIC fixture images ({@code test/resources/vision})
 * — five deliberately different cases the model must read: a dense watchlist, a
 * portfolio with P/L, a sparse single-stock chart, a news-article card, a text-heavy
 * macro post. Assertions are loose (the model phrases freely) — they only check that
 * the relevant entity/text was actually read, which is what we can't unit-test.
 *
 * <p>Auto-runs locally when the isolated Ollama is available; skips in CI.
 * <pre>mvn test -pl agent -Dtest=VisionIT -Dtest.excludedGroups=</pre>
 */
@Tag("integration")
@EnabledIf("de.bsommerfeld.wsbg.terminal.agent.OllamaAvailability#available")
class VisionIT {

    private static AgentBrain brain;
    private static LocalImageServer images;

    @BeforeAll
    static void up() throws Exception {
        OllamaAvailability.ensureOllama();
        brain = new AgentBrain(new GlobalConfig(), new ApplicationEventBus(), new OllamaServerManager(), new LlmGate());
        images = new LocalImageServer();
    }

    @AfterAll
    static void down() {
        if (images != null) images.close();
    }

    private static String see(String fixture) {
        String r = brain.describeImage(images.url(fixture));
        return r == null ? "" : r.toLowerCase(Locale.ROOT);
    }

    @Test
    void watchlistNamesAreRead() {
        String r = see("watchlist-red.png");
        assertFalse(r.isBlank(), "vision returned nothing for the watchlist");
        assertTrue(r.contains("nvidia") || r.contains("apple") || r.contains("microsoft"),
                "watchlist should surface a held name — got: " + r);
    }

    @Test
    void portfolioHoldingIsRead() {
        String r = see("portfolio-gains.png");
        assertTrue(r.contains("nvidia") || r.contains("siemens") || r.contains("ftse") || r.contains("ohb"),
                "portfolio should surface a holding — got: " + r);
    }

    @Test
    void singleStockTickerIsRead() {
        String r = see("single-stock-intel.png");
        assertTrue(r.contains("intel"), "single-stock page should read Intel — got: " + r);
    }

    @Test
    void newsArticleSubjectIsRead() {
        String r = see("article-marvell.png");
        assertTrue(r.contains("marvell"), "article card should read Marvell — got: " + r);
    }

    @Test
    void macroPostSubjectIsRead() {
        String r = see("macro-qqq.png");
        assertTrue(r.contains("qqq") || r.contains("trump") || r.contains("tariff") || r.contains("zoll"),
                "macro post should surface QQQ/Trump/tariffs — got: " + r);
    }
}
