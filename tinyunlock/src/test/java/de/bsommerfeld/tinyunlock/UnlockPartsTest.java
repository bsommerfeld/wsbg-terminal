package de.bsommerfeld.tinyunlock;

import org.cef.network.CefCookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The engine's parts that need no Chromium. */
class UnlockPartsTest {

    @TempDir
    Path directory;

    @Test
    void recognisesCaptchasButNotTheScriptCheck() {
        assertTrue(PageCheck.isCaptcha("<title>Reddit - Prove your humanity</title>"));
        assertTrue(PageCheck.isCaptcha("<div class=\"g-recaptcha\" data-sitekey=\"x\"></div>"));
        assertTrue(PageCheck.isCaptcha("<script src=\"https://challenges.cloudflare.com/turnstile/v0/api.js\">"));
        // Reddit's script check submits itself - a browser passes it, nobody has to.
        assertFalse(PageCheck.isCaptcha("<title>Reddit</title><script>e.elements.namedItem(\"solution\")</script>"));
        assertFalse(PageCheck.isCaptcha("<title>Reddit - The heart of the internet</title>"));
    }

    @Test
    void cookiesBecomeNetscapeLines() {
        Date expires = new Date(1_800_000_000_000L);
        List<String> lines = UnlockMain.toLines(List.of(
                new CefCookie("loid", "abc", ".reddit.com", "/", true, false, new Date(), new Date(), true, expires),
                new CefCookie("token_v2", "t", ".reddit.com", "/", true, true, new Date(), new Date(), true, expires),
                new CefCookie("g_state", "s", "www.reddit.com", "", false, false, new Date(), new Date(), false, null)));

        assertEquals(".reddit.com\tTRUE\t/\tTRUE\t1800000000\tloid\tabc", lines.get(0));
        assertEquals("#HttpOnly_.reddit.com\tTRUE\t/\tTRUE\t1800000000\ttoken_v2\tt", lines.get(1));
        assertEquals("www.reddit.com\tFALSE\t/\tFALSE\t0\tg_state\ts", lines.get(2));
    }

    @Test
    void registrableDomainMatchesTinyFetchs() {
        assertEquals("reddit.com", UnlockMain.site("www.reddit.com"));
        assertEquals("example.co.uk", UnlockMain.site("www.example.co.uk"));
    }

    @Test
    void argumentsAndTheirDefaults() {
        UnlockMain.Arguments headless = UnlockMain.Arguments.parse(new String[] {
                "--url", "https://www.reddit.com/", "--bundle", "/b", "--profile", "/p", "--await", "loid, token_v2"});
        assertEquals(Set.of("loid", "token_v2"), headless.await());
        assertFalse(headless.visible());
        assertEquals(45, headless.timeoutSeconds());

        UnlockMain.Arguments visible = UnlockMain.Arguments.parse(new String[] {
                "--url", "u", "--bundle", "b", "--profile", "p", "--visible", "true", "--window-title", "Bestätigung"});
        assertTrue(visible.visible());
        assertEquals("Bestätigung", visible.windowTitle());
        assertEquals(600, visible.timeoutSeconds(), "a person gets time");

        assertTrue(UnlockMain.Arguments.parse(new String[] {"--bundle", "b", "--profile", "p", "--prepare", "true"})
                .prepare(), "preparing needs no page");
        assertThrows(IllegalArgumentException.class, () -> UnlockMain.Arguments.parse(new String[] {"--url", "u"}));
        assertThrows(IllegalArgumentException.class, () -> UnlockMain.Arguments.parse(new String[] {
                "--url", "u", "--bundle", "b", "--profile", "p", "--bogus", "x"}));
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
