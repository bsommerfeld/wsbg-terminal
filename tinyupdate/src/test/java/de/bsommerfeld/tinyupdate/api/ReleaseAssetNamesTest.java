package de.bsommerfeld.tinyupdate.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReleaseAssetNamesTest {

    @Test
    void defaultStreamKeepsThePlainNames() {
        assertEquals(new ReleaseAssetNames("update.json", "files.zip", "app.zip", "deps.zip"),
                ReleaseAssetNames.DEFAULT);
    }

    @Test
    void platformStreamSharesTheAppArchive() {
        assertEquals(new ReleaseAssetNames(
                        "wsbg-windows-x86_64-update.json",
                        "wsbg-windows-x86_64-files.zip",
                        "wsbg-app.zip",
                        "wsbg-windows-x86_64-deps.zip"),
                ReleaseAssetNames.of("wsbg", "windows-x86_64"));
    }

    @Test
    void prefixWithoutPlatform() {
        assertEquals("launcher-update.json", ReleaseAssetNames.of("launcher", "").manifest());
        assertEquals("launcher-files.zip", ReleaseAssetNames.of("launcher", null).archive());
    }
}
