package de.bsommerfeld.tinyfetch.engine;

import de.bsommerfeld.tinyfetch.api.Step;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * @param steps         what the browser does for it - without {@link Step#LOAD_PAGE}
 *                      there is no page, and {@code anchor} is not used
 */
public record EngineRequest(long id, String url, String method, List<Map.Entry<String, String>> headers,
        byte[] body, String anchor, long timeoutMillis, Set<Step> steps) {

    public EngineRequest {
        headers = List.copyOf(headers);
        steps = steps.isEmpty() ? Step.ALL : Collections.unmodifiableSet(EnumSet.copyOf(steps));
    }
}
