package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end coverage of the OCR image read as WIRED (successor of the retired
 * VisionIT): {@link AgentBrain#describeImage} over a real HTTP fetch
 * ({@link LocalImageServer}) into the per-URL cache — full-resolution fetch,
 * Tesseract read, cache hit on re-ask. The model is never invoked; Ollama is
 * only needed because constructing the brain boots the managed server.
 *
 * <p>Needs a system Tesseract AND the isolated Ollama; skips otherwise.
 * <pre>mvn test -pl agent -Dtest=OcrWiringIT -Dtest.excludedGroups=</pre>
 */
@Tag("integration")
@EnabledIf("de.bsommerfeld.wsbg.terminal.agent.OllamaAvailability#available")
class OcrWiringIT {

    private static AgentBrain brain;
    private static LocalImageServer images;

    @BeforeAll
    static void up() throws Exception {
        OllamaAvailability.ensureOllama();
        brain = new AgentBrain(new GlobalConfig(), new ApplicationEventBus(), new OllamaServerManager(), new LlmGate());
        assumeTrue(brain.imageReadingAvailable(), "no system Tesseract install — skipping OCR wiring IT");
        images = new LocalImageServer();
    }

    @AfterAll
    static void down() {
        if (images != null) images.close();
    }

    @Test
    void describeImageReadsInstrumentEvidenceOverHttp() {
        String url = images.url("watchlist-red.png");
        String text = brain.describeImage(url).toLowerCase(Locale.ROOT);
        assertTrue(text.contains("nvidia") || text.contains("apple") || text.contains("microsoft"),
                "wired OCR read should surface a watchlist name — got: " + text);
        assertTrue(brain.isImageCached(url), "read result should land in the per-URL cache");
        assertEquals(text, brain.describeImageIfCached(url).toLowerCase(Locale.ROOT),
                "cache-only readers should see the same text");
    }

    @Test
    void brokenImageCachesAsEmptyNeverAnErrorString() {
        String url = images.url("does-not-exist.png");
        assertEquals("", brain.describeImage(url),
                "a failed read must cache as empty — no error prose may leak toward reports");
        assertTrue(brain.isImageCached(url), "the failure should be remembered for the session");
    }
}
