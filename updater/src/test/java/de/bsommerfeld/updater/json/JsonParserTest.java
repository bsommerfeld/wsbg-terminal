package de.bsommerfeld.updater.json;

import de.bsommerfeld.updater.model.FileEntry;
import de.bsommerfeld.updater.model.UpdateManifest;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}
