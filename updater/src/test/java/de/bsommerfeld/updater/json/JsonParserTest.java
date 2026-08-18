package de.bsommerfeld.updater.json;

import de.bsommerfeld.updater.model.FileEntry;
import de.bsommerfeld.updater.model.UpdateManifest;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;

class JsonParserTest {

    private static final String VALID_MANIFEST = """
            {
              "version": "1.2.0",
              "files": [
                { "path": "lib/core.jar", "sha256": "abc123def456", "size": 12345 },
                { "path": "lib/util.jar", "sha256": "789xyz000", "size": 67890 }
              ]
            }
            """;

    // -- parseManifest --

    @Test
    void parseManifest_shouldExtractVersion() {
        UpdateManifest manifest = JsonParser.parseManifest(VALID_MANIFEST);
        assertEquals("1.2.0", manifest.version());
    }

    @Test
    void parseManifest_shouldExtractAllFiles() {
        UpdateManifest manifest = JsonParser.parseManifest(VALID_MANIFEST);
        assertEquals(2, manifest.files().size());
    }

    @Test
    void parseManifest_shouldExtractFileDetails() {
        UpdateManifest manifest = JsonParser.parseManifest(VALID_MANIFEST);
        FileEntry first = manifest.files().get(0);

        assertEquals("lib/core.jar", first.path());
        assertEquals("abc123def456", first.sha256());
        assertEquals(12345, first.size());
    }

    @Test
    void parseManifest_shouldHandleSingleFile() {
        String json = """
                { "version": "0.1.0", "files": [{ "path": "app.jar", "sha256": "hash", "size": 100 }] }
                """;
        UpdateManifest manifest = JsonParser.parseManifest(json);
        assertEquals(1, manifest.files().size());
    }

    @Test
    void parseManifest_shouldHandleEmptyFilesArray() {
        String json = """
                { "version": "0.1.0", "files": [] }
                """;
        UpdateManifest manifest = JsonParser.parseManifest(json);
        assertTrue(manifest.files().isEmpty());
    }

    @Test
    void parseManifest_shouldThrowForMissingVersion() {
        String json = """
                { "files": [{ "path": "a", "sha256": "b", "size": 1 }] }
                """;
        assertThrows(JsonParseException.class, () -> JsonParser.parseManifest(json));
    }

    @Test
    void parseManifest_shouldThrowForMissingFilesArray() {
        String json = """
                { "version": "1.0.0" }
                """;
        assertThrows(JsonParseException.class, () -> JsonParser.parseManifest(json));
    }

    @Test
    void parseManifest_shouldThrowForMalformedJson() {
        assertThrows(JsonParseException.class, () -> JsonParser.parseManifest("not json at all"));
    }

    // -- extractString --

    @Test
    void extractString_shouldExtractSimpleValue() {
        String json = """
                { "tag_name": "v2.0.0" }
                """;
        assertEquals("v2.0.0", JsonParser.extractString(json, "tag_name"));
    }

    @Test
    void extractString_shouldThrowForMissingKey() {
        assertThrows(JsonParseException.class,
                () -> JsonParser.extractString("{}", "missing_key"));
    }

    @Test
    void extractString_shouldHandleSpacesAroundColon() {
        String json = """
                { "key" : "value" }
                """;
        assertEquals("value", JsonParser.extractString(json, "key"));
    }

    @Test
    void extractString_shouldExtractFirstOccurrence() {
        String json = """
                { "name": "first", "other": "x", "name": "second" }
                """;
        // Index-based search finds the first occurrence
        assertEquals("first", JsonParser.extractString(json, "name"));
    }

    // -- Large file sizes --

    @Test
    void parseManifest_shouldHandleLargeFileSizes() {
        String json = """
                { "version": "1.0.0", "files": [{ "path": "big.jar", "sha256": "hash", "size": 2147483648 }] }
                """;
        UpdateManifest manifest = JsonParser.parseManifest(json);
        assertEquals(2147483648L, manifest.files().get(0).size());
    }

    // -- Nested brackets --

    @Test
    void parseManifest_shouldHandleExtraWhitespace() {
        String json = """
                {
                    "version" :  "3.0.0"  ,
                    "files" : [
                        {
                            "path"   : "lib/a.jar" ,
                            "sha256" : "aaa" ,
                            "size"   : 999
                        }
                    ]
                }
                """;
        UpdateManifest manifest = JsonParser.parseManifest(json);
        assertEquals("3.0.0", manifest.version());
        assertEquals(1, manifest.files().size());
        assertEquals("lib/a.jar", manifest.files().get(0).path());
    }

    // -- Release asset extraction --

    @Test
    void extractAssetUrl_shouldFindAssetByName() {
        String json = """
                {
                  "tag_name": "v1.0",
                  "assets": [
                    { "name": "update.json", "browser_download_url": "https://cdn.example.com/update.json" },
                    { "name": "files.zip", "browser_download_url": "https://cdn.example.com/files.zip" }
                  ]
                }
                """;
        assertEquals("https://cdn.example.com/files.zip",
                JsonParser.extractAssetUrl(json, "files.zip"));
    }

    @Test
    void extractAssetUrl_shouldReturnNullForMissingAsset() {
        String json = """
                { "assets": [{ "name": "other.txt", "browser_download_url": "https://x/o.txt" }] }
                """;
        assertNull(JsonParser.extractAssetUrl(json, "update.json"));
    }

    @Test
    void extractAssetUrl_shouldIgnoreBodyTextMimickingAssets() {
        // The release body is free markdown — quoted key-lookalikes and
        // brackets inside it must not confuse the scan. (JSON escapes the
        // inner quotes, so the raw text contains \" sequences.)
        String json = """
                {
                  "tag_name": "v1.0",
                  "body": "Changelog [1] mentions \\"name\\": \\"update.json\\" and a fake \\"browser_download_url\\": \\"https://evil.example/x\\" {oops]",
                  "assets": [
                    { "name": "update.json", "browser_download_url": "https://cdn.example.com/real.json" }
                  ]
                }
                """;
        assertEquals("https://cdn.example.com/real.json",
                JsonParser.extractAssetUrl(json, "update.json"));
    }

    @Test
    void extractAssetUrl_shouldIgnoreNestedUploaderObjects() {
        // Keys inside nested objects (e.g. uploader) must not shadow the
        // asset's own top-level name/url.
        String json = """
                {
                  "assets": [
                    {
                      "uploader": { "name": "update.json", "browser_download_url": "https://evil.example/y" },
                      "name": "update.json",
                      "browser_download_url": "https://cdn.example.com/real.json"
                    }
                  ]
                }
                """;
        assertEquals("https://cdn.example.com/real.json",
                JsonParser.extractAssetUrl(json, "update.json"));
    }

    @Test
    void extractAssetUrl_shouldReturnNullWithoutAssetsArray() {
        assertNull(JsonParser.extractAssetUrl("{ \"tag_name\": \"v1.0\" }", "update.json"));
    }

    // -- firstPublishedRelease (the experimental channel's source) --

    private static final String RELEASE_LISTING = """
            [
              { "tag_name": "v3.0.0-rc1", "draft": true,  "prerelease": true,
                "body": "not ready", "assets": [] },
              { "tag_name": "v2.9.0-rc2", "draft": false, "prerelease": true,
                "body": "try it", "assets": [
                  { "name": "update.json", "browser_download_url": "https://cdn/rc2.json" }
                ] },
              { "tag_name": "v2.8.0", "draft": false, "prerelease": false,
                "body": "stable", "assets": [] }
            ]
            """;

    @Test
    void firstPublishedRelease_shouldSkipDraftsButKeepPreReleases() {
        String release = JsonParser.firstPublishedRelease(RELEASE_LISTING);
        assertEquals("v2.9.0-rc2", JsonParser.extractString(release, "tag_name"));
    }

    @Test
    void firstPublishedRelease_shouldReturnAnObjectTheAssetReaderCanUse() {
        // The whole point: the picked entry goes through the same pipeline a
        // single-release payload does.
        String release = JsonParser.firstPublishedRelease(RELEASE_LISTING);
        assertEquals("https://cdn/rc2.json", JsonParser.extractAssetUrl(release, "update.json"));
    }

    @Test
    void firstPublishedRelease_shouldIgnoreDraftWordingInsideTheBody() {
        String listing = """
                [
                  { "tag_name": "v1.0.0", "body": "still a \\"draft\\": true, honestly",
                    "assets": [] }
                ]
                """;
        assertEquals("v1.0.0",
                JsonParser.extractString(JsonParser.firstPublishedRelease(listing), "tag_name"));
    }

    @Test
    void firstPublishedRelease_shouldReturnNullWhenEverythingIsADraft() {
        assertNull(JsonParser.firstPublishedRelease(
                "[ { \"tag_name\": \"v1.0\", \"draft\": true, \"assets\": [] } ]"));
    }

    @Test
    void firstPublishedRelease_shouldReturnNullForAnEmptyListing() {
        assertNull(JsonParser.firstPublishedRelease("[]"));
    }
}
