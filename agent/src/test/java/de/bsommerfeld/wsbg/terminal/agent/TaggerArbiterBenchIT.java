package de.bsommerfeld.wsbg.terminal.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;

/**
 * D3 — the tagger gauge of the offline test stand ({@code .script/bench.sh}):
 * arbiter precision over the labelled sense cases of the 2026-08-13 live run,
 * replayed through the REAL {@code article-sense} prompt against a local Ollama.
 *
 * <p>Baseline: 2 of 5 ABOUT verdicts wrong (both the "company name doubles as
 * business vocabulary" class — Outlook Therapeutics approved on generic quarter
 * words) = 40% wrong-ABOUT rate.
 *
 * <p>The sample titles per case are reconstructed (the live run logged only the
 * cluster words); they carry the same signal shape — generic quarter vocabulary
 * without the instrument's name for the failure class, the instrument's own world
 * for the correct ones.
 *
 * <p>The deeper basin replay stays {@link de.bsommerfeld.wsbg.terminal.agent.tagging.BasinBenchIT}
 * (needs {@code -Dtagging.basin}); {@code bench.sh --basin DIR} chains it.
 *
 * <p>Run: {@code .script/bench.sh} — or directly:
 * {@code mvn test -pl agent -Dtest=TaggerArbiterBenchIT -Dtest.excludedGroups= -Dbench.enabled=true}
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "bench.enabled", matches = "true")
class TaggerArbiterBenchIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    record Case(String instrument, List<String> terms, List<String> titles, boolean expectAbout) {}

    private static final List<Case> CASES = List.of(
            // The 2026-08-13 failure class: generic quarter vocabulary, no referent.
            new Case("Outlook Therapeutics, Inc. (OTLK)",
                    List.of("here", "published"),
                    List.of("Read the full report published here",
                            "The results were published here earlier today"),
                    false),
            new Case("Outlook Therapeutics, Inc. (OTLK)",
                    List.of("despite", "drive", "growth", "now", "positive", "sales"),
                    List.of("Sales growth continues despite headwinds",
                            "Positive momentum expected to drive growth now"),
                    false),
            new Case("DICK'S SPORTING GOODS, INC. (DKS)",
                    List.of("continued", "cost", "inflation", "july", "prices"),
                    List.of("Producer prices rose in July on continued cost inflation",
                            "Inflation data for July shows prices climbing"),
                    false),
            new Case("Gold (GC=F)",
                    List.of("johannes", "liebmann", "medaille"),
                    List.of("Johannes Liebmann jubelt über Gold bei der EM",
                            "Gold und Silber für Liebmann und die Staffel"),
                    false),
            // The correct verdicts of the same run — precision must not be bought
            // by rejecting everything.
            new Case("DAX",
                    List.of("leitindex", "mittagsboerse", "punkten", "rekordhoch"),
                    List.of("Mittagsbörse: Leitindex DAX klettert auf Rekordhoch",
                            "DAX legt um 240 Punkte zu und markiert Allzeithoch"),
                    true),
            new Case("HSBC Holdings plc (HSBC)",
                    List.of("trinkaus", "westlb"),
                    List.of("HSBC Trinkaus & Burkhardt übernimmt WestLB-Geschäft",
                            "Trinkaus-Tochter der HSBC baut Deutschland-Geschäft aus"),
                    true),
            new Case("BlackRock, Inc. (BLK)",
                    List.of("billionen", "fink", "vermoegensverwalter"),
                    List.of("Vermögensverwalter BlackRock: Larry Fink über die Billionen-Frage",
                            "Wie gefährlich ist der größte Vermögensverwalter der Welt?"),
                    true),
            new Case("Gold (GC=F)",
                    List.of("silber", "unze", "notenbanken"),
                    List.of("Goldpreis: Unze erstmals über 4.000 Dollar, Silber zieht mit",
                            "Notenbanken kaufen Gold in Rekordtempo"),
                    true));

    @Test
    void measure() throws Exception {
        String model = System.getProperty("bench.model", "gemma4:e4b-mlx");
        String sys = PromptLoader.loadLocalized("article-sense", "de");
        int n = 0;
        int correct = 0;
        int aboutSaid = 0;
        int aboutWrong = 0;
        for (Case c : CASES) {
            Boolean about = ask(model, sys, c);
            if (about == null) {
                System.out.println("[BENCH:D3] Ollama unreachable — skipped after " + n + " case(s)");
                return;
            }
            n++;
            boolean ok = about == c.expectAbout();
            if (ok) correct++;
            if (about) {
                aboutSaid++;
                if (!c.expectAbout()) aboutWrong++;
            }
            System.out.printf("    %s × %s → %s (erwartet %s)%s%n",
                    c.instrument(), c.terms(), about ? "ABOUT" : "FOREIGN",
                    c.expectAbout() ? "ABOUT" : "FOREIGN", ok ? "" : "  ✗");
        }
        System.out.printf(Locale.ROOT,
                "[BENCH:D3] Arbiter (model=%s): %d/%d korrekt (%.0f%%), falsche ABOUT-Urteile"
                        + " %d/%d (Basiswert des Livelaufs: 2/5 = 40%%)%n",
                model, correct, n, 100.0 * correct / n, aboutWrong, Math.max(1, aboutSaid));
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(3)).build();

    /** One arbiter call with the production prompt/user shape; null when unreachable. */
    private static Boolean ask(String model, String sys, Case c) {
        try {
            StringBuilder user = new StringBuilder("INSTRUMENT: ").append(c.instrument()).append('\n');
            user.append("CONTEXT WORDS: ").append(String.join(", ", c.terms())).append('\n');
            user.append("HEADLINES:\n");
            for (String t : c.titles()) {
                user.append("- ").append(t).append('\n');
            }
            var body = JSON.createObjectNode();
            body.put("model", model).put("stream", false).put("think", false);
            body.put("format", "json");
            var opts = body.putObject("options");
            opts.put("temperature", 0.2).put("top_p", 0.9).put("top_k", 40).put("num_predict", 512);
            var msgs = body.putArray("messages");
            msgs.addObject().put("role", "system").put("content", sys);
            msgs.addObject().put("role", "user").put("content", user.toString());
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:11434/api/chat"))
                    .timeout(java.time.Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            String content = JSON.readTree(resp.body()).path("message").path("content").asText("");
            var obj = JsonReplies.parseJson(content);
            return obj != null && obj.path("about").asBoolean(false);
        } catch (Exception e) {
            return null;
        }
    }
}
