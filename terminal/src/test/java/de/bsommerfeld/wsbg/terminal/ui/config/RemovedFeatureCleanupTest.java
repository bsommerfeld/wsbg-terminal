package de.bsommerfeld.wsbg.terminal.ui.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one-time cleanup of the retired deep dive / watchlist / weather data:
 * it must take exactly the named leftovers, leave the live data untouched, and
 * never run twice.
 */
class RemovedFeatureCleanupTest {

    @TempDir
    Path appData;

    private void file(String relative) throws IOException {
        Path p = appData.resolve(relative);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "x");
    }

    @BeforeEach
    void seed() throws IOException {
        // leftovers of the removed features
        file("deepdive-neubau/run-1/leg/report.json");
        file("deepdive-neubau/index.jsonl");
        file("watchlist.json");
        file("watchlist.json.tmp");
        file("archive/deepdive-reports.jsonl");
        file("archive/weather-reports.jsonl");
        file("archive/flow-snapshots.jsonl");
        file("images/logos/AAPL.png");
        file("tmp/dd-print-123.html");
        file("dd-phase-times.csv");

        // live data that must survive
        file("archive/headlines.jsonl");
        file("snapshots/reddit.json");
        file("market-events.jsonl");
        file("adhoc-events.jsonl");
        file("fear-greed-history.jsonl");
        file("instruments/aliases.json");
        file("fonts/Inter.ttf");
        file("ollama/models/blob");
        file("tesseract/eng.traineddata");
        file("cef-bundle/cef.pak");
        file("config.toml");
        file("tmp/webfetch-cache.html");
    }

    @Test
    @DisplayName("removes every named leftover of the retired features")
    void removesLeftovers() {
        RemovedFeatureCleanup.run(appData);

        assertFalse(Files.exists(appData.resolve("deepdive-neubau")));
        assertFalse(Files.exists(appData.resolve("watchlist.json")));
        assertFalse(Files.exists(appData.resolve("watchlist.json.tmp")));
        assertFalse(Files.exists(appData.resolve("archive/deepdive-reports.jsonl")));
        assertFalse(Files.exists(appData.resolve("archive/weather-reports.jsonl")));
        assertFalse(Files.exists(appData.resolve("archive/flow-snapshots.jsonl")));
        assertFalse(Files.exists(appData.resolve("images/logos")));
        assertFalse(Files.exists(appData.resolve("tmp/dd-print-123.html")));
        assertFalse(Files.exists(appData.resolve("dd-phase-times.csv")));
    }

    @Test
    @DisplayName("touches nothing outside the named paths")
    void keepsLiveData() {
        RemovedFeatureCleanup.run(appData);

        for (String kept : new String[] {
                "archive/headlines.jsonl", "snapshots/reddit.json", "market-events.jsonl",
                "adhoc-events.jsonl", "fear-greed-history.jsonl", "instruments/aliases.json",
                "fonts/Inter.ttf", "ollama/models/blob", "tesseract/eng.traineddata",
                "cef-bundle/cef.pak", "config.toml", "tmp/webfetch-cache.html" })
            assertTrue(Files.exists(appData.resolve(kept)), kept + " must survive the cleanup");
    }

    @Test
    @DisplayName("runs exactly once - a second start leaves recreated data alone")
    void runsOnlyOnce() throws IOException {
        RemovedFeatureCleanup.run(appData);
        assertTrue(Files.exists(appData.resolve(".removed-features-cleaned")));

        file("watchlist.json");
        RemovedFeatureCleanup.run(appData);

        assertTrue(Files.exists(appData.resolve("watchlist.json")),
                "the marker must short-circuit every later start");
    }

    @Test
    @DisplayName("a fresh installation with nothing to clean still succeeds")
    void emptyDirIsFine(@TempDir Path fresh) {
        RemovedFeatureCleanup.run(fresh);
        assertTrue(Files.exists(fresh.resolve(".removed-features-cleaned")));
    }
}
