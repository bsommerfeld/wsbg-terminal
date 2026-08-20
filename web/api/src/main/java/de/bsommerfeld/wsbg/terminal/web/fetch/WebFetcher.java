package de.bsommerfeld.wsbg.terminal.web.fetch;

import java.time.Duration;
import java.util.Map;

/**
 * THE house fetcher — the one door every source's request goes through. A
 * caller names the URL and its transport order ({@link FetchUtil} array, tried
 * left to right); the fetcher owns everything transport-independent exactly
 * once: host cooldowns after 429s, per-host transport silencing, conditional
 * caching (ETag/If-Modified-Since), and the retry policy. Sources never build
 * their own HTTP client and never re-implement any of this.
 *
 * <p>Contract mirrors {@link Transport}: error statuses come back in the
 * {@link WebResponse}, throwing is reserved for transport failures after all
 * modes were exhausted.
 */
public interface WebFetcher {

    /**
     * Fetches {@code url} over the given transport order.
     *
     * @param url     absolute URL
     * @param headers request headers (may be empty, never null)
     * @param timeout per-request ceiling, applied to each transport attempt
     * @param modes   ordered transports to try. Naming none means the house
     *                default {@code DIRECT → BROWSER} — the order for plain
     *                consumers (article digester, image path, corpus) that are
     *                not self-describing sources; sources always pass their
     *                declared {@code mode()}.
     */
    WebResponse fetch(String url, Map<String, String> headers, Duration timeout, FetchUtil... modes)
            throws Exception;

    /**
     * Like {@link #fetch} but the response bytes stay undecoded — the image
     * path. Only {@link FetchUtil#DIRECT}-capable transports serve binary;
     * a mode that cannot is skipped.
     */
    WebResponse fetchBinary(String url, Map<String, String> headers, Duration timeout, FetchUtil... modes)
            throws Exception;

    /**
     * POST with a body — the door for the few API-shaped outliers (GraphQL,
     * RPC search) that a GET cannot address. Same cooldown/silence economy as
     * {@link #fetch}; never conditionally cached. Modes whose transport
     * cannot carry a body are skipped.
     */
    WebResponse post(String url, Map<String, String> headers, String body, String contentType,
            Duration timeout, FetchUtil... modes) throws Exception;

    /**
     * {@code true} while the host of {@code url} sits in an active cooldown,
     * so a polite caller can skip a doomed request without burning a socket.
     */
    boolean hostCoolingDown(String url);

    /**
     * Reports that {@code url} answered with a wall the status line did not
     * admit to — a consent interstitial, a bot challenge, a JS shell where a
     * feed was promised. The fetcher cannot recognise these (they arrive as a
     * healthy 2xx), so the source that knows what a real answer looks like
     * hands the verdict back and the host goes on cooldown like any other
     * block. Without it the house keeps hammering a wall at full cadence.
     *
     * <p>Default is a no-op so a fetcher that owns no host memory (the
     * open-web leg) stays valid.
     */
    default void reportWall(String url) {
        // no memory to teach
    }
}
