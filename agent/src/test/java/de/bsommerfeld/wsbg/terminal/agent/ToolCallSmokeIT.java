package de.bsommerfeld.wsbg.terminal.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE TOOL-CALLING MINI-EXPERIMENT (user mandate 2026-07-17, the mandatory
 * pre-step before ANY agentic DD build): does gemma4:e4b under Ollama do
 * reliable tool calls - Sand oder Fels?
 *
 * <p>This is a MODEL-CAPABILITY probe against the REAL local Ollama server
 * (never a pipeline verification, never a synthetic scraper): it speaks the
 * raw {@code /api/chat} tool protocol directly, independent of the app's chat
 * client, because the question under test is the MODEL + its template - the
 * plumbing comes later, and only if this answers "Fels".
 *
 * <p>Three measurements, mirroring the agreed protocol:
 * <ol>
 *   <li><b>Template support</b> - does one forced lookup come back as a
 *       structured {@code tool_calls} entry with parseable arguments?</li>
 *   <li><b>Loop discipline</b> - a closed lookup task over several rounds:
 *       does the model call only EXISTING keys, avoid re-fetching what it
 *       already saw, terminate, and answer correctly? Metrics printed.</li>
 *   <li><b>Scratchpad hygiene</b> - note, list, strike: does it work a tiny
 *       queue down without losing or inventing entries?</li>
 * </ol>
 *
 * <p>Run: {@code TOOLCALL_SMOKE=true mvn test -pl agent -Dtest=ToolCallSmokeIT
 * -Dtest.excludedGroups=} (Ollama must be up; override the server via
 * {@code OLLAMA_HOST}, the model via {@code TOOLCALL_MODEL}).
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "TOOLCALL_SMOKE", matches = "true")
class ToolCallSmokeIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private static final String BASE = System.getenv().getOrDefault(
            "OLLAMA_HOST", "http://127.0.0.1:11434");
    private static final String MODEL = System.getenv().getOrDefault(
            "TOOLCALL_MODEL", "gemma4:e4b");

    private static final int MAX_ROUNDS = 20;

    // ---- 1. template support ----

    @Test
    void templateCarriesToolCalls() throws Exception {
        ArrayNode tools = MAPPER.createArrayNode();
        tools.add(toolDef("artikel", "Liefert den Volltext des Artikels mit dem Marker.",
                "marker", "integer", "Der Quellenmarker, z. B. 3"));
        List<ObjectNode> messages = new ArrayList<>();
        messages.add(msg("system", "Du bist ein Research-Assistent. Nutze das Werkzeug "
                + "artikel, um Artikeltexte zu lesen - rate nie."));
        messages.add(msg("user", "Was steht in Artikel 3? Lies ihn mit dem Werkzeug."));

        JsonNode reply = chat(messages, tools);
        JsonNode calls = reply.path("message").path("tool_calls");
        System.out.println("[TOOLCALL] template probe reply: " + reply.path("message"));
        assertTrue(calls.isArray() && !calls.isEmpty(),
                "no structured tool_calls - the template does not carry tools (SAND): "
                        + reply.path("message"));
        JsonNode fn = calls.get(0).path("function");
        assertEquals("artikel", fn.path("name").asText(), "wrong tool name");
        assertEquals(3, fn.path("arguments").path("marker").asInt(),
                "marker argument not parseable: " + fn.path("arguments"));
    }

    // ---- 2. loop discipline over a closed lookup world ----

    @Test
    void lookupLoopDiscipline() throws Exception {
        Map<Integer, String> articles = new LinkedHashMap<>();
        articles.put(1, "Der Konzern eröffnete ein neues Werk in Ungarn und stellt 400 "
                + "Mitarbeiter ein.");
        articles.put(2, "Die Hauptversammlung stimmte der Dividende von 0,80 EUR je Aktie zu.");
        articles.put(3, "Der Vorstand kündigte einen Aktienrückkauf über 50 Millionen EUR an.");
        articles.put(4, "Die FDA setzte die Entscheidung über den Zulassungsantrag auf den "
                + "29. Juli an.");
        articles.put(5, "Der Quartalsumsatz stieg um 12 Prozent auf 480 Millionen EUR.");

        ArrayNode tools = MAPPER.createArrayNode();
        tools.add(toolDef("artikel", "Liefert den Volltext des Artikels mit dem Marker (1-5).",
                "marker", "integer", "Der Quellenmarker, 1 bis 5"));

        List<ObjectNode> messages = new ArrayList<>();
        messages.add(msg("system", "Du bist ein Research-Assistent. Lies Artikel NUR über "
                + "das Werkzeug artikel(marker) - rate nie. Wenn du die Antwort kennst, "
                + "antworte mit genau einer Zeile: ANTWORT: <marker>"));
        messages.add(msg("user", "Welcher der Artikel 1 bis 5 nennt einen FDA-Termin? "
                + "Prüfe die Artikel und antworte dann wie vereinbart."));

        int rounds = 0;
        int invalidKeys = 0;
        int repeats = 0;
        List<Integer> fetched = new ArrayList<>();
        String finalAnswer = null;
        while (rounds < MAX_ROUNDS) {
            rounds++;
            JsonNode message = chat(messages, tools).path("message");
            messages.add((ObjectNode) message);
            JsonNode calls = message.path("tool_calls");
            if (calls.isArray() && !calls.isEmpty()) {
                for (JsonNode call : calls) {
                    int marker = call.path("function").path("arguments").path("marker").asInt(-1);
                    String result;
                    if (!articles.containsKey(marker)) {
                        invalidKeys++;
                        result = "FEHLER: kein Artikel mit Marker " + marker;
                    } else {
                        if (fetched.contains(marker)) repeats++;
                        fetched.add(marker);
                        result = articles.get(marker);
                    }
                    ObjectNode toolMsg = msg("tool", result);
                    toolMsg.put("tool_name", "artikel");
                    messages.add(toolMsg);
                }
                continue;
            }
            String content = message.path("content").asText("");
            if (content.contains("ANTWORT:")) {
                finalAnswer = content;
                break;
            }
            // Neither a call nor the agreed answer: nudge once per round.
            messages.add(msg("user", "Nutze artikel(marker) oder antworte mit "
                    + "'ANTWORT: <marker>'."));
        }

        System.out.printf("[TOOLCALL] loop metrics: rounds=%d fetched=%s invalidKeys=%d "
                + "repeats=%d answer=%s%n", rounds, fetched, invalidKeys, repeats, finalAnswer);
        assertNotNull(finalAnswer, "no final answer within " + MAX_ROUNDS + " rounds (SAND)");
        assertTrue(finalAnswer.contains("4"), "wrong article picked: " + finalAnswer);
        assertEquals(0, invalidKeys, "invented markers (key fidelity failed)");
        assertTrue(repeats <= 2, "excessive re-fetching (" + repeats + " repeats)");
    }

    // ---- 3. scratchpad hygiene ----

    @Test
    void scratchpadHygiene() throws Exception {
        Map<Integer, String> pad = new LinkedHashMap<>();
        int[] nextId = {1};

        ArrayNode tools = MAPPER.createArrayNode();
        tools.add(toolDef("notiere", "Legt ein Vorhaben auf dem Scratchpad an und liefert "
                + "seine Nummer.", "text", "string", "Das Vorhaben in einem Satz"));
        tools.add(toolDef("streiche", "Streicht das erledigte Vorhaben mit der Nummer.",
                "nummer", "integer", "Die Nummer des Vorhabens"));

        List<ObjectNode> messages = new ArrayList<>();
        messages.add(msg("system", "Du führst ein Scratchpad NUR über die Werkzeuge "
                + "notiere(text) und streiche(nummer). Wenn alles erledigt ist, antworte "
                + "mit genau einer Zeile: FERTIG"));
        messages.add(msg("user", "Lege für jede dieser drei Aufgaben ein Vorhaben an, "
                + "streiche danach jedes einzeln und antworte dann wie vereinbart: "
                + "(a) Kursverlauf prüfen, (b) FDA-Termin verifizieren, "
                + "(c) Short-Quote nachschlagen."));

        int rounds = 0;
        int created = 0;
        int struck = 0;
        int invalid = 0;
        boolean done = false;
        while (rounds < MAX_ROUNDS && !done) {
            rounds++;
            JsonNode message = chat(messages, tools).path("message");
            messages.add((ObjectNode) message);
            JsonNode calls = message.path("tool_calls");
            if (calls.isArray() && !calls.isEmpty()) {
                for (JsonNode call : calls) {
                    String name = call.path("function").path("name").asText();
                    JsonNode args = call.path("function").path("arguments");
                    String result;
                    if ("notiere".equals(name)) {
                        int id = nextId[0]++;
                        pad.put(id, args.path("text").asText(""));
                        created++;
                        result = "Vorhaben " + id + " angelegt.";
                    } else if ("streiche".equals(name)) {
                        int id = args.path("nummer").asInt(-1);
                        if (pad.remove(id) != null) {
                            struck++;
                            result = "Vorhaben " + id + " gestrichen.";
                        } else {
                            invalid++;
                            result = "FEHLER: kein offenes Vorhaben " + id;
                        }
                    } else {
                        invalid++;
                        result = "FEHLER: unbekanntes Werkzeug " + name;
                    }
                    ObjectNode toolMsg = msg("tool", result);
                    toolMsg.put("tool_name", name);
                    messages.add(toolMsg);
                }
                continue;
            }
            if (message.path("content").asText("").contains("FERTIG")) done = true;
        }

        System.out.printf("[TOOLCALL] scratchpad metrics: rounds=%d created=%d struck=%d "
                + "invalid=%d leftover=%s done=%b%n", rounds, created, struck, invalid, pad, done);
        assertTrue(done, "never declared FERTIG within " + MAX_ROUNDS + " rounds (SAND)");
        assertEquals(3, created, "expected exactly 3 Vorhaben");
        assertTrue(pad.isEmpty(), "left open Vorhaben on the pad: " + pad);
        assertEquals(0, invalid, "invalid scratchpad operations");
    }

    // ---- plumbing ----

    private static ObjectNode msg(String role, String content) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("role", role);
        node.put("content", content);
        return node;
    }

    private static ObjectNode toolDef(String name, String description,
            String param, String type, String paramDescription) {
        ObjectNode fn = MAPPER.createObjectNode();
        fn.put("name", name);
        fn.put("description", description);
        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");
        ObjectNode prop = params.putObject("properties").putObject(param);
        prop.put("type", type);
        prop.put("description", paramDescription);
        params.putArray("required").add(param);
        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("type", "function");
        tool.set("function", fn);
        return tool;
    }

    private static JsonNode chat(List<ObjectNode> messages, ArrayNode tools) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", MODEL);
        ArrayNode msgs = body.putArray("messages");
        for (ObjectNode m : messages) msgs.add(m);
        if (tools != null) body.set("tools", tools);
        body.put("stream", false);
        body.putObject("options").put("temperature", 0.1);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/api/chat"))
                .timeout(Duration.ofMinutes(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(),
                "Ollama /api/chat answered HTTP " + response.statusCode()
                        + " - server up? " + response.body());
        return MAPPER.readTree(response.body());
    }
}
