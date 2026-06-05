package de.bsommerfeld.wsbg.terminal.core.util;

import java.security.SecureRandom;
import java.util.List;

/**
 * Supplies a random, realistic desktop-browser {@code User-Agent} string for the
 * anonymous scraping clients (Yahoo, FinancialJuice, finanznachrichten, EUR/USD,
 * image fetches).
 *
 * <p>
 * These endpoints don't expose an API key; they simply block requests whose
 * User-Agent looks like a bot (empty, a bare HTTP-library default, etc.). A
 * single hard-coded browser string works, but every install then shares one
 * identical fingerprint — easy to block wholesale. Picking a value at random
 * from a pool of <b>real, current</b> browser strings spreads installs across
 * many fingerprints while each one still passes as a genuine browser, so the
 * request is still accepted. (This is the generic, browser-shaped analogue of
 * {@code RedditUserAgent}, which builds a Reddit-convention API string instead.)
 *
 * <h3>Stability</h3>
 * Each {@link #random()} call returns a fresh pick. Callers should grab one
 * <em>once</em> (e.g. into a {@code final} field at construction) and reuse it
 * for the client's lifetime: a real browser keeps a stable User-Agent within a
 * session, so rotating it on every request would itself look bot-like. The
 * randomness is meant to vary <em>across</em> installs and process restarts, not
 * request-to-request.
 *
 * <h3>Keeping the pool fresh</h3>
 * Browser versions age. The strings below are real releases from 2024–2025;
 * refresh them periodically so they keep reading as current browsers. They are
 * deliberately internally consistent (matching engine/OS tokens) — an
 * implausible combination is a bot tell.
 */
public final class BrowserUserAgent {

    /**
     * Real, current desktop-browser User-Agent strings spanning Chrome, Edge,
     * Firefox and Safari across Windows 10/11 and macOS. Every entry is a
     * genuine, internally-consistent string that real browsers send.
     */
    private static final List<String> POOL = List.of(
            // Chrome — Windows
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
            // Chrome — macOS
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36",
            // Edge — Windows (Chromium base + Edg/ token)
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0",
            // Firefox — Windows
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0",
            // Firefox — macOS
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:132.0) Gecko/20100101 Firefox/132.0",
            // Safari — macOS
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15");

    private static final SecureRandom RANDOM = new SecureRandom();

    private BrowserUserAgent() {}

    /**
     * A random, realistic browser User-Agent. Call once and reuse for the
     * lifetime of a client (see the stability note in the class doc).
     */
    public static String random() {
        return POOL.get(RANDOM.nextInt(POOL.size()));
    }

    /** The immutable pool of candidate strings (exposed for tests). */
    public static List<String> pool() {
        return POOL;
    }
}
