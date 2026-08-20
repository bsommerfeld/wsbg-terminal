package de.bsommerfeld.updater.endpoint;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The "test connection" behind the endpoint fields: does this address answer,
 * and which models does it have?
 *
 * <p>
 * It lives in {@code updater} - the module the launcher and the terminal both
 * see - because BOTH ask the question: the launcher's advanced sheet before it
 * writes an address into the config, and the terminal's settings before it
 * commits to one. The launcher cannot reach {@code agent} or {@code core} at
 * all (it must start when nothing else is installed yet), so the alternative
 * was two copies of the same probe, drifting. Same reasoning as
 * {@code ModelCatalog}.
 *
 * <p>
 * It exists because the alternative is a support case. Everything that can go
 * wrong with an external endpoint - a typo in the host, the wrong port, a
 * sleeping machine, a proxy wanting a header, a model tag that is not on that
 * server - fails identically from the user's side: the terminal simply stops
 * producing headlines. One button that answers "yes, and here is what is
 * installed" turns all of those into a five-second check.
 *
 * <p>
 * The model list is the second half of the point: it is what fills the model
 * field, so nobody has to type a tag by hand and be wrong about it.
 */
public final class EndpointProbe {

    /** Short on purpose - this runs while a human watches a button. */
    private static final Duration TIMEOUT = Duration.ofSeconds(6);

    /** The two chat protocols an endpoint can speak. Mirrors {@code AiEndpoint.Api}. */
    public enum Api {
        OLLAMA, OPENAI
    }

    /**
     * @param ok      whether the server answered with a usable model list
     * @param api     which protocol answered - the whole reason the probe tries
     *                both: people know their server's ADDRESS, not whether it
     *                speaks Ollama's API or OpenAI's, and asking them is asking
     *                the wrong question
     * @param reason  the failure in the server's or the network's own words,
     *                empty when {@code ok}
     * @param models  the models it offers, empty when unreachable
     */
    public record Result(boolean ok, Api api, String reason, List<String> models) {
    }

    private EndpointProbe() {
    }

    /**
     * Probes {@code url}. Never throws: every failure mode is a {@link Result}
     * the settings panel can render.
     *
     * @param url        as typed by the user - normalized here, so "box:11434"
     *                   works exactly as it will once saved
     * @param authHeader header name, or empty for none
     * @param authValue  header value, sent verbatim
     */
    public static Result probe(String url, String authHeader, String authValue) {
        String base = normalizeUrl(url == null ? "" : url);
        if (base.isEmpty()) return new Result(false, Api.OLLAMA, "no address", List.of());

        // Ollama first: it is the protocol the pipeline was tuned on, so where
        // a server speaks both (Ollama serves an OpenAI-compatible /v1 too) the
        // better of the two wins without the user having to know there was a
        // choice.
        Attempt ollama = tryApi(base + "/api/tags", Api.OLLAMA, authHeader, authValue);
        if (ollama.result().ok()) return ollama.result();

        // Nothing answered at all - refused, timed out, wrong host. Asking the
        // same silent address a second question only doubles the wait: measured
        // 12 s against a frozen server (2 x the 6 s timeout), and that wait sits
        // in front of the boot probe AND the settings' test button. A second
        // protocol is only worth trying when the host IS there and merely
        // lacks that path.
        if (!ollama.answered()) return ollama.result();

        Attempt openAi = tryApi(base + "/v1/models", Api.OPENAI, authHeader, authValue);
        if (openAi.result().ok()) return openAi.result();

        // Both refused. The Ollama attempt is the one to report: it ran first,
        // so its reason describes the ADDRESS rather than the second attempt's
        // 404 on a path that was never going to exist.
        return ollama.result();
    }

    /**
     * One attempt plus whether the server ANSWERED at all - an HTTP status,
     * even a bad one, means the host is up and it is worth asking it something
     * else. Silence means it is not.
     */
    private record Attempt(Result result, boolean answered) {
    }

    /** One protocol's model-list endpoint. Never throws - failures are Attempts. */
    private static Attempt tryApi(String listUrl, Api api, String authHeader, String authValue) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(listUrl))
                    .timeout(TIMEOUT)
                    .GET();
            if (authValue != null && !authValue.isBlank()) {
                String name = authHeader == null || authHeader.isBlank()
                        ? "Authorization" : authHeader.strip();
                request.header(name, authValue.strip());
            }
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build()
                    .send(request.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                // 401/403 is the single most likely misconfiguration after a
                // typo, and it is worth saying so plainly rather than "HTTP
                // 401" - the user's next move is a header, not a debugger.
                return new Attempt(new Result(false, api, "HTTP " + response.statusCode()
                        + (response.statusCode() == 401 || response.statusCode() == 403
                                ? " (authentication required?)" : ""),
                        List.of()), true);
            }
            List<String> models = parseModelNames(response.body());
            if (models.isEmpty()) {
                // Reachable but empty: a server with nothing loaded. Not a
                // failure we can call one, but naming it beats a green tick
                // over an endpoint that cannot answer a single request.
                return new Attempt(new Result(true, api, "no models installed", List.of()), true);
            }
            return new Attempt(new Result(true, api, "", models), true);
        } catch (Exception e) {
            // toString(), not getMessage(): a ConnectException carries none.
            String message = e.getMessage();
            return new Attempt(new Result(false, api,
                    message == null || message.isBlank() ? e.toString() : message.strip(),
                    List.of()), false);
        }
    }

    /**
     * Accepts what a human types. A bare {@code 192.168.1.20:11434} is a host,
     * not a URL, and {@code URI.create} would parse it as a scheme - so a
     * missing scheme becomes {@code http://}, and a trailing slash goes,
     * because every call site appends its own {@code /api/...} path.
     *
     * <p>The ONE definition, deliberately: the launcher sheet, the settings
     * panel and the runtime endpoint must all read a typed address the same
     * way, or a connection test passes on a string the app then calls
     * differently.
     */
    public static String normalizeUrl(String raw) {
        String url = raw == null ? "" : raw.strip();
        if (url.isEmpty()) return "";
        if (!url.matches("(?i)^https?://.*")) {
            url = "http://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        // A trailing /v1 is the OpenAI API's own prefix, not part of the
        // server's address - and half the documentation out there quotes it, so
        // people paste it in. Kept, it turns every lookup into a wrong one:
        // /v1/api/tags and /v1/v1/models (measured, not guessed - the wiring
        // test caught exactly this). The canonical form is the bare address;
        // the OpenAI client re-appends the prefix itself.
        if (url.regionMatches(true, url.length() - 3, "/v1", 0, 3)) {
            url = url.substring(0, url.length() - 3);
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
        }
        return url;
    }

    /**
     * Pulls the model names out of a listing - {@code "name"} in Ollama's
     * {@code /api/tags}, {@code "id"} in OpenAI's {@code /v1/models}. Both are
     * accepted from either endpoint, because some servers answer one path in
     * the other's shape and guessing wrong would mean an empty model list on a
     * perfectly healthy server.
     *
     * <p>A regex rather than a JSON parse: the shape is two levels deep, the
     * runtime reads {@code /api/tags} the same way, and a probe must not fail
     * because a future version added a field.
     */
    static List<String> parseModelNames(String json) {
        List<String> names = new ArrayList<>();
        if (json == null) return names;
        Matcher m = Pattern.compile("\"(?:name|id)\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        while (m.find()) {
            String name = m.group(1);
            if (!names.contains(name)) names.add(name);
        }
        return names;
    }

}
