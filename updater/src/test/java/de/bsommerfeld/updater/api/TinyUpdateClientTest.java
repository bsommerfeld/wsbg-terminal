package de.bsommerfeld.updater.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TinyUpdateClient focusing on local filesystem logic:
 * version recording, version reading, and the findAssetUrl parsing.
 * HTTP-dependent methods are not tested here — they require a mock server.
 */
class TinyUpdateClientTest {

  @TempDir
  Path appDir;

  private TinyUpdateClient client;

  @BeforeEach
  void setUp() {
    client = new TinyUpdateClient(
        GitHubRepository.of("owner/repo"), appDir);
  }

  @Test
  void currentVersion_shouldReturnNullWhenNoVersionFile() {
    assertNull(client.currentVersion());
  }

  @Test
  void currentVersion_shouldReadVersionFromFile() throws IOException {
    Files.writeString(appDir.resolve("version.txt"), "v2.0.0");
    assertEquals("v2.0.0", client.currentVersion());
  }

  @Test
  void currentVersion_shouldStripWhitespace() throws IOException {
    Files.writeString(appDir.resolve("version.txt"), "  v1.5.0  \n");
    assertEquals("v1.5.0", client.currentVersion());
  }

  @Test
  void findAssetUrl_shouldParseGitHubReleaseJson() throws Exception {
    String releaseJson = """
        {
          "tag_name": "v1.0",
          "assets": [
            {
              "name": "update.json",
              "browser_download_url": "https://cdn.example.com/update.json"
            },
            {
              "name": "files.zip",
              "browser_download_url": "https://cdn.example.com/files.zip"
            }
          ]
        }
        """;

    // Use reflection to test the private static method
    var method = TinyUpdateClient.class.getDeclaredMethod("findAssetUrl", String.class, String.class);
    method.setAccessible(true);

    String updateUrl = (String) method.invoke(null, releaseJson, "update.json");
    assertEquals("https://cdn.example.com/update.json", updateUrl);

    String zipUrl = (String) method.invoke(null, releaseJson, "files.zip");
    assertEquals("https://cdn.example.com/files.zip", zipUrl);
  }

  @Test
  void findAssetUrl_shouldThrowForMissingAsset() throws Exception {
    String releaseJson = """
        {
          "tag_name": "v1.0",
          "assets": [{"name": "other.txt", "browser_download_url": "https://x.com/other.txt"}]
        }
        """;

    var method = TinyUpdateClient.class.getDeclaredMethod("findAssetUrl", String.class, String.class);
    method.setAccessible(true);

    var ex = assertThrows(Exception.class,
        () -> method.invoke(null, releaseJson, "update.json"));
    assertTrue(ex.getCause() instanceof IOException);
  }
}
