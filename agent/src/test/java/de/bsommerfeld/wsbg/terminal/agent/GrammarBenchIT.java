package de.bsommerfeld.wsbg.terminal.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * D1 — the grammar gauge of the offline test stand ({@code .script/bench.sh}).
 *
 * <p>Two parts, one number set:
 * <ul>
 *   <li><b>Archive baseline</b> (always): the four indicators that measurably track
 *       the manually-determined defect rate (scratch analysis 1.4: 26b-era 3.8%
 *       defects vs e4b-era 47.4%) computed over the archived wire lines, split at
 *       the 2026-08-11 model switch.</li>
 *   <li><b>Live replay</b> (when a local Ollama answers): the REAL compose prompt
 *       ({@code headline-compose-unit.de}) over briefs reconstructed from the
 *       archive (subject, ticker, snapshot, news refs; the archived line stands in
 *       as the room evidence snippet — the one part the archive cannot carry), run
 *       against {@code -Dbench.model} (default the Apple-Silicon default tag), and
 *       the same four indicators over the fresh output.</li>
 * </ul>
 *
 * <p>Run: {@code .script/bench.sh} — or directly:
 * {@code mvn test -pl agent -Dtest=GrammarBenchIT -Dtest.excludedGroups= -Dbench.enabled=true}
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "bench.enabled", matches = "true")
class GrammarBenchIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 2026-08-11 00:00 UTC — the model switch (26b → e4b) per the run analysis. */
    private static final long ERA_CUT_EPOCH = 1786406400L;

    private static final Pattern SHOW_PREDICATE =
            Pattern.compile("\\b(zeigt|zeigte|zeigen|signalisier\\w*)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONNECTOR =
            Pattern.compile("\\b(während|doch|da|obwohl|weil|nachdem)\\b");
    private static final Pattern COMMA_BEFORE_CONNECTOR =
            Pattern.compile(",\\s*(während|doch|da|obwohl|weil|nachdem)\\b");

    @Test
    void measure() throws Exception {
        Path archive = Path.of(System.getProperty("bench.archive",
                System.getProperty("user.home")
                        + "/Library/Application Support/wsbg-terminal/archive/headlines.jsonl"));
        if (!Files.exists(archive)) {
            System.out.println("[BENCH:D1] archive missing at " + archive + " — skipped");
            return;
        }
        List<JsonNode> lines = readArchive(archive);

        Stats era26 = new Stats();
        Stats eraE4 = new Stats();
        for (JsonNode n : lines) {
            (n.path("createdAt").asLong(0) < ERA_CUT_EPOCH ? era26 : eraE4)
                    .add(n.path("headline").asText(""));
        }
        System.out.println("[BENCH:D1] archive baseline (indicators from grammatik-analyse 1.4):");
        era26.print("    26b-Ära ");
        eraE4.print("    e4b-Ära ");

        System.out.println("[BENCH:D1] archive trigger baseline (as production wrote it):");
        printTriggerDist("    archiv  ", lines, n -> n.path("trigger").asText("NONE"),
                n -> n.path("highlight").asText("NORMAL"));

        String model = System.getProperty("bench.model", "gemma4:e4b-mlx");
        int n = Integer.getInteger("bench.compose.n", 20);
        List<JsonNode> sample = sampleWithSnapshots(lines, n);
        if (sample.isEmpty()) {
            System.out.println("[BENCH:D1] no archive entries with snapshots — live replay skipped");
            return;
        }
        // The prompt under test. Default is the shipped resource; -Dbench.prompt.file
        // points at a file instead, so an A/B against an older revision needs no
        // source edit (git show <rev>:<path> > /tmp/old.txt).
        String promptFile = System.getProperty("bench.prompt.file", "");
        String sys = (promptFile.isBlank()
                        ? PromptLoader.loadLocalized("headline-compose-unit", "de")
                        : Files.readString(Path.of(promptFile), StandardCharsets.UTF_8))
                .replace("{{LANGUAGE}}", "Deutsch");
        if (!promptFile.isBlank()) System.out.println("[BENCH:D1] prompt override: " + promptFile);
        Stats live = new Stats();
        java.util.Map<String, Integer> liveTriggers = new java.util.LinkedHashMap<>();
        int liveRed = 0;
        int calls = 0;
        long briefChars = 0;
        int citedLines = 0;
        int parsedLines = 0;
        for (JsonNode entry : sample) {
            String brief = briefOf(entry);
            briefChars += brief.length();
            if (Boolean.getBoolean("bench.print.brief")) {
                System.out.println("    ---- brief ----\n" + brief.indent(4) + "    ---------------");
            }
            String reply = ollama(model, sys, brief);
            if (reply == null) {
                System.out.println("[BENCH:D1] Ollama unreachable — live replay skipped after "
                        + calls + " call(s)");
                break;
            }
            calls++;
            ComposeReplyParser.ParsedCompose pc = ComposeReplyParser.parse(reply, false);
            if (pc.draft() != null) {
                live.add(pc.draft().headline());
                parsedLines++;
                if (pc.newsUsed() != null && !pc.newsUsed().isEmpty()) citedLines++;
                // The red gauge: the model's own trigger/highlight, held against the
                // one production wrote for the SAME subject — the reconciler is not in
                // this path, so this is the model's raw call.
                String t = HighlightReconciler.normalizeTrigger(pc.draft().trigger());
                String h = String.valueOf(pc.draft().highlight()).toUpperCase(Locale.ROOT);
                liveTriggers.merge(t.isBlank() ? "NONE" : t, 1, Integer::sum);
                if (h.contains("IMPORTANT")) liveRed++;
                System.out.printf(Locale.ROOT, "    [%s] %s → %s/%s%n",
                        entry.path("tickerSymbol").asText(""),
                        entry.path("trigger").asText("NONE"), t, h);
                System.out.println("    → " + pc.draft().headline());
            } else {
                live.whiffs++;
                System.out.println("    → (no usable headline) " + clip(reply));
            }
        }
        if (calls > 0) {
            System.out.printf(Locale.ROOT,
                    "[BENCH:D1] live replay: model=%s, %d call(s), %d whiff(s)%n",
                    model, calls, live.whiffs);
            live.print("    live    ");
            StringBuilder td = new StringBuilder();
            liveTriggers.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
                    .forEach(e -> td.append(e.getKey()).append(' ').append(e.getValue()).append("  "));
            System.out.printf(Locale.ROOT, "    live    Rot %.1f%% (%d von %d) | Trigger: %s%n",
                    parsedLines == 0 ? 0.0 : 100.0 * liveRed / parsedLines, liveRed, parsedLines,
                    td.toString().trim());
            // Teil-2 gauges: brief size (chars ≈ tokens·4) and the citation share
            // (lines that leaned on at least one [N#] item).
            System.out.printf(Locale.ROOT,
                    "    brief   Ø %.0f chars (≈%.0f tokens) | Zitatquote (newsUsed≠[]) %.1f%%"
                            + " von %d Zeilen%n",
                    (double) briefChars / calls, briefChars / calls / 4.0,
                    parsedLines == 0 ? 0.0 : 100.0 * citedLines / parsedLines, parsedLines);
        }
    }

    // ------------------------------------------------------------------ indicators

    /** Connector inventory for the distribution gauge — no single one should carry >⅓. */
    private static final String[] CONNECTOR_KINDS =
            {"während", "nachdem", "obwohl", "trotz", "da", "weil", "aber", "doch", "und"};

    private static final class Stats {
        int n;
        int showPredicate;
        int connectorLines;
        int commaConnector;
        int affen;
        int fullStop;
        long words;
        int whiffs;
        final java.util.Map<String, Integer> connectorDist = new java.util.LinkedHashMap<>();

        void add(String headline) {
            if (headline == null || headline.isBlank()) return;
            n++;
            if (SHOW_PREDICATE.matcher(headline).find()) showPredicate++;
            if (CONNECTOR.matcher(headline).find()) {
                connectorLines++;
                if (COMMA_BEFORE_CONNECTOR.matcher(headline).find()) commaConnector++;
            }
            if (headline.contains("Affen")) affen++;
            if (headline.trim().endsWith(".")) fullStop++;
            words += headline.trim().split("\\s+").length;
            String kind = "(ohne)";
            String hl = " " + headline.toLowerCase(Locale.ROOT) + " ";
            for (String c : CONNECTOR_KINDS) {
                if (Pattern.compile("[\\s,(]" + c + "[\\s,]").matcher(hl).find()) {
                    kind = c;
                    break;
                }
            }
            connectorDist.merge(kind, 1, Integer::sum);
        }

        void print(String label) {
            if (n == 0) {
                System.out.println(label + " n=0");
                return;
            }
            System.out.printf(Locale.ROOT,
                    "%s n=%d | zeigt/signalisiert-Prädikat %.1f%% | Komma vor Konnektor %.1f%%"
                            + " (auf %d Konnektor-Zeilen) | Satzschluss-Punkt %.1f%% | Affen %.1f%%"
                            + " | Ø Wörter %.1f%n",
                    label, n, 100.0 * showPredicate / n,
                    connectorLines == 0 ? 0.0 : 100.0 * commaConnector / connectorLines,
                    connectorLines, 100.0 * fullStop / n, 100.0 * affen / n, (double) words / n);
            StringBuilder d = new StringBuilder();
            connectorDist.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .forEach(e -> d.append(String.format(Locale.ROOT, "%s %.0f%%  ",
                            e.getKey(), 100.0 * e.getValue() / n)));
            System.out.println(label + " Konnektoren: " + d.toString().trim());
        }
    }

    /** Trigger + red share over a set of archived lines — the production baseline. */
    private static void printTriggerDist(String label, List<JsonNode> lines,
            java.util.function.Function<JsonNode, String> trigger,
            java.util.function.Function<JsonNode, String> highlight) {
        java.util.Map<String, Integer> dist = new java.util.LinkedHashMap<>();
        int red = 0;
        for (JsonNode n : lines) {
            dist.merge(trigger.apply(n), 1, Integer::sum);
            if ("IMPORTANT".equals(highlight.apply(n))) red++;
        }
        StringBuilder d = new StringBuilder();
        dist.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> d.append(e.getKey()).append(' ').append(e.getValue()).append("  "));
        System.out.printf(Locale.ROOT, "%s n=%d | Rot %.1f%% (%d) | Trigger: %s%n",
                label, lines.size(), lines.isEmpty() ? 0.0 : 100.0 * red / lines.size(), red,
                d.toString().trim());
    }

    // ------------------------------------------------------------------ archive → brief

    private static List<JsonNode> readArchive(Path archive) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(archive, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    out.add(JSON.readTree(line));
                } catch (Exception ignored) {
                    // torn line
                }
            }
        }
        return out;
    }

    /** The newest {@code n} entries that carry a ticker + a priced snapshot. */
    private static List<JsonNode> sampleWithSnapshots(List<JsonNode> lines, int n) {
        // -Dbench.only=RDDT,WDAY narrows the replay to named tickers — a probe on the
        // handful of cases a change is actually about, without a 40-call full run.
        Set<String> only = new java.util.LinkedHashSet<>();
        for (String s : System.getProperty("bench.only", "").split(",")) {
            if (!s.isBlank()) only.add(s.trim().toUpperCase(Locale.ROOT));
        }
        List<JsonNode> out = new ArrayList<>();
        for (int i = lines.size() - 1; i >= 0 && out.size() < n; i--) {
            JsonNode e = lines.get(i);
            if (e.path("tickerSymbol").asText("").isBlank()) continue;
            if (!only.isEmpty() && !only.contains(e.path("tickerSymbol").asText(""))) continue;
            if (!e.path("snapshot").isObject()) continue;
            if (e.path("headline").asText("").isBlank()) continue;
            out.add(e);
        }
        return out;
    }

    /** Rebuilds the unit brief through the REAL {@link UnitBriefWriter} path. */
    private static String briefOf(JsonNode e) {
        String ticker = e.path("tickerSymbol").asText("");
        String name = e.path("subjects").isArray() && e.path("subjects").size() > 0
                ? e.path("subjects").get(0).path("name").asText(ticker) : ticker;
        SubjectUnit unit = new SubjectUnit(ticker, name);
        JsonNode s = e.path("snapshot");
        MarketSnapshot snap = new MarketSnapshot(
                s.path("symbol").asText(ticker), s.path("price").asDouble(),
                s.path("previousClose").asDouble(), s.path("dayChangePercent").asDouble(),
                s.path("dayHigh").asDouble(), s.path("dayLow").asDouble(),
                s.path("volume").asLong(-1), s.path("fiftyTwoWeekHigh").asDouble(),
                s.path("fiftyTwoWeekLow").asDouble(), s.path("currency").asText("EUR"),
                s.path("exchangeName").asText(""), s.path("marketTimeEpochSeconds").asLong(0),
                List.of());
        List<Article> news = new ArrayList<>();
        int i = 0;
        for (JsonNode ref : e.path("newsRefs")) {
            news.add(new Article("bench-n" + (++i), ref.path("title").asText(""),
                    ref.path("publisher").asText(""), ref.path("url").asText(""),
                    Instant.ofEpochSecond(ref.path("publishedAt").asLong(0)), List.of(),
                    null, "", false));
        }
        unit.updateResolved(name, ticker, snap, news);
        // The archive carries no room text — the archived line stands in as the one
        // evidence snippet, so the model has an event to re-tell. The indicators
        // measure FORM (predicate, comma, register), which this preserves.
        unit.addEvidence(new SubjectUnit.EvidenceRef("bench", "c1",
                e.path("headline").asText(""), "reddit", Instant.now().getEpochSecond()));
        return UnitBriefWriter.unitBrief(unit, true, BriefLabels.of("de"), null);
    }

    // ------------------------------------------------------------------ ollama

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(3)).build();

    /** One raw compose call, same sampling as production; null when unreachable. */
    private static String ollama(String model, String sys, String user) {
        try {
            var body = JSON.createObjectNode();
            body.put("model", model).put("stream", false).put("think", false);
            body.put("format", "json");
            var opts = body.putObject("options");
            opts.put("temperature", 0.2).put("top_p", 0.9).put("top_k", 40)
                    .put("num_predict", 3584);
            var msgs = body.putArray("messages");
            msgs.addObject().put("role", "system").put("content", sys);
            msgs.addObject().put("role", "user").put("content", user);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(System.getProperty("bench.ollama.url",
                            "http://localhost:11434") + "/api/chat"))
                    .timeout(java.time.Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            return JSON.readTree(resp.body()).path("message").path("content").asText("");
        } catch (Exception e) {
            return null;
        }
    }

    private static String clip(String s) {
        String t = s == null ? "" : s.strip();
        return t.length() <= 120 ? t : t.substring(0, 120) + "…";
    }
}
