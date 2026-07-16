package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real-Tesseract OCR coverage over the STATIC fixture images
 * ({@code test/resources/vision}) — the same five cases the retired vision model
 * had to read: a dark-mode watchlist, a light-mode portfolio with WKNs, a sparse
 * dark single-stock view, a news-article card, a text-heavy macro post.
 * Assertions check ONLY that instrument evidence (names, tickers, WKNs) lands in
 * the raw OCR text — layout fidelity and prose quality are explicitly not the bar.
 *
 * <p>Needs a system Tesseract (e.g. {@code brew install tesseract}); skips otherwise.
 * <pre>mvn test -pl agent -Dtest=OcrEngineIT -Dtest.excludedGroups=</pre>
 */
@Tag("integration")
class OcrEngineIT {

    private static OcrEngine engine;

    @BeforeAll
    static void up() {
        engine = new OcrEngine();
        assumeTrue(engine.available(), "no system Tesseract install — skipping OCR IT");
    }

    private static String read(String fixture) throws Exception {
        BufferedImage img = ImageIO.read(OcrEngineIT.class.getResource("/vision/" + fixture));
        String text = engine.read(img);
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    @Test
    void darkModeWatchlistNamesAreRead() throws Exception {
        String r = read("watchlist-red.png");
        int hits = countHits(r, "nvidia", "apple", "microsoft", "sap", "ceres power", "abivax");
        assertTrue(hits >= 3, "dark watchlist should surface most held names — got: " + r);
    }

    @Test
    void portfolioNamesAndWknsAreRead() throws Exception {
        String r = read("portfolio-gains.png");
        int names = countHits(r, "nvidia", "siemens", "ohb", "amundi", "vang");
        assertTrue(names >= 3, "portfolio should surface most position names — got: " + r);
        assertTrue(r.contains("918422") || r.contains("723610") || r.contains("a2pkxg"),
                "portfolio should surface at least one WKN — got: " + r);
    }

    @Test
    void darkSingleStockNameIsRead() throws Exception {
        String r = read("single-stock-intel.png");
        assertTrue(r.contains("intel"), "single-stock view should surface Intel — got: " + r);
    }

    @Test
    void articleCardEntityIsRead() throws Exception {
        String r = read("article-marvell.png");
        assertTrue(r.contains("marvell"), "article card should surface Marvell — got: " + r);
    }

    @Test
    void macroPostTickerIsRead() throws Exception {
        String r = read("macro-qqq.png");
        assertTrue(r.contains("qqq"), "macro post should surface the QQQ ticker — got: " + r);
    }

    private static int countHits(String text, String... needles) {
        int hits = 0;
        for (String n : needles) {
            if (text.contains(n)) hits++;
        }
        return hits;
    }
}
