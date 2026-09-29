package de.bsommerfeld.tinyfetch.engine;

import java.util.List;
import java.util.Map;

/**
 * One request as the engine receives it: what the page-side {@code fetch()}
 * is to ask for.
 *
 * @param id            correlates the answer; unique per engine connection
 * @param url           the address
 * @param method        {@code GET}, {@code POST}, ...
 * @param headers       the caller's own headers, names lower-case; the engine
 *                      drops the ones a browser owns itself
 * @param body          the body, or {@code null} without one
 * @param anchor        the page to park the tab on, or {@code null} for the
 *                      root of {@code url}'s own origin
 * @param timeoutMillis how long the page may wait for the answer
 */
public record EngineRequest(long id, String url, String method, List<Map.Entry<String, String>> headers,
        byte[] body, String anchor, long timeoutMillis) {

    public EngineRequest {
        headers = List.copyOf(headers);
    }
}
