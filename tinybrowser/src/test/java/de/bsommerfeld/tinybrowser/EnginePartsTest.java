package de.bsommerfeld.tinybrowser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The engine's parts that need no Chromium. */
class EnginePartsTest {

    @TempDir
    Path directory;

    @Test
    void argumentsNeedSocketChromiumAndProfile() {
        BrowserMain.Arguments arguments = BrowserMain.Arguments.parse(new String[] {
                "--socket", "/tmp/s", "--chromium", "/c", "--profile", "/p", "--seed-profile", "/old"});
        assertEquals(Path.of("/tmp/s"), arguments.socket());
        assertEquals(Path.of("/old"), arguments.seedProfile());
        assertThrows(IllegalArgumentException.class,
                () -> BrowserMain.Arguments.parse(new String[] {"--chromium", "/c", "--profile", "/p"}));
        assertThrows(IllegalArgumentException.class, () -> BrowserMain.Arguments.parse(new String[] {
                "--socket", "s", "--chromium", "c", "--profile", "p", "--bogus", "x"}));
    }

    @Test
    void originsAreSchemeHostAndPort() {
        assertEquals("https://www.reddit.com", Tabs.originOf("https://WWW.Reddit.com/r/x/new.json?limit=5"));
        assertEquals("http://127.0.0.1:8080", Tabs.originOf("http://127.0.0.1:8080/"));
        assertEquals(null, Tabs.originOf("not a url"));
    }

    @Test
    void aSocketParksOnItsOwnHostsRoot() {
        assertEquals("https://stream.example.org:9443/", Sockets.anchorOf("wss://stream.example.org:9443/ws/a?b=c"));
        assertEquals("http://127.0.0.1:8080/", Sockets.anchorOf("ws://127.0.0.1:8080/echo"),
                "an insecure socket needs an insecure page");
        assertEquals(null, Sockets.anchorOf("ftp://example.org/"));
        assertEquals(null, Sockets.anchorOf("not a url"));
    }

    @Test
    void pageHeadersArriveAsPairs() {
        char pair = PageFetch.HEADER_DELIMITER;
        assertEquals(List.of(Map.entry("content-type", "application/json"), Map.entry("x-empty", "")),
                PageFetch.headers("content-type" + pair + "application/json" + pair + "x-empty" + pair));
        assertTrue(PageFetch.headers("").isEmpty());
    }

    @Test
    void onlyRefusalsCountAsRefusals() {
        assertTrue(Tab.isRestricted(403));
        assertTrue(Tab.isRestricted(429));
        assertTrue(Tab.isRestricted(503));
        assertFalse(Tab.isRestricted(404), "a missing page is an answer - the site let the tab in");
        assertFalse(Tab.isRestricted(200));
    }

    @Test
    void onlyAVisitorCheckIsWorthASecondVisit() {
        assertTrue(Tab.wantsRevisit(403), "Reddit's first visit - the second one passes");
        assertFalse(Tab.wantsRevisit(429), "a rate limit - a reload is one more request against it");
        assertFalse(Tab.wantsRevisit(503));
        assertFalse(Tab.wantsRevisit(200));
    }

    @Test
    void warmupBackoffDoublesUpToItsCeilingButHonoursALongerRetryAfter() {
        assertEquals(5_000, Tab.warmupBackoff(2_500, List.of()));
        assertEquals(60_000, Tab.warmupBackoff(40_000, List.of()));
        assertEquals(30_000, Tab.warmupBackoff(2_500, List.of(Map.entry("Retry-After", "30"))));
        assertEquals(120_000, Tab.warmupBackoff(2_500, List.of(Map.entry("Retry-After", "120"))),
                "a site asking for more than the ceiling gets it");
    }

    @Test
    void seedCopiesTheProfileOnceAndLeavesLocksAndCachesBehind() throws Exception {
        Path old = directory.resolve("old");
        Files.createDirectories(old.resolve("Default/Local Storage"));
        Files.createDirectories(old.resolve("Default/GPUCache"));
        Files.writeString(old.resolve("Default/Local Storage/leveldb"), "state");
        Files.writeString(old.resolve("Default/GPUCache/data_0"), "cache");
        Files.writeString(old.resolve("SingletonLock"), "host-123");
        Files.writeString(old.resolve("Local State"), "{}");

        Path profile = directory.resolve("profile");
        assertTrue(ProfileSeed.seed(old, profile));

        assertEquals("state", Files.readString(profile.resolve("Default/Local Storage/leveldb")));
        assertTrue(Files.exists(profile.resolve("Local State")));
        assertFalse(Files.exists(profile.resolve("SingletonLock")));
        assertFalse(Files.exists(profile.resolve("Default/GPUCache")));

        Files.writeString(old.resolve("Local State"), "{\"changed\":true}");
        assertFalse(ProfileSeed.seed(old, profile), "once the profile lives, it is never overwritten");
        assertEquals("{}", Files.readString(profile.resolve("Local State")));
    }

    @Test
    void missingSeedIsNoSeed() throws Exception {
        assertFalse(ProfileSeed.seed(directory.resolve("nothing-here"), directory.resolve("profile")));
    }
}
