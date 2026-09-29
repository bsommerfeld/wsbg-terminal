package de.bsommerfeld.tinyfetch.engine;

import java.util.List;
import java.util.Map;

/**
 * The engine's answer to one {@link EngineRequest}: either what the host
 * answered - any status - or why there was no answer at all.
 *
 * @param id      the request's id
 * @param status  the HTTP status; {@code 0} when {@code failure} is set
 * @param url     where the answer came from, after redirects
 * @param headers the response headers as the page saw them, names lower-case
 * @param body    the body, empty without one
 * @param failure why there is no HTTP answer, or {@code null} when there is one
 */
public record EngineAnswer(long id, int status, String url, List<Map.Entry<String, String>> headers, byte[] body,
        String failure) {

    public EngineAnswer {
        headers = List.copyOf(headers);
    }

    /** No HTTP answer, for the reason given. */
    public static EngineAnswer failed(long id, String failure) {
        return new EngineAnswer(id, 0, "", List.of(), new byte[0], failure);
    }
}
