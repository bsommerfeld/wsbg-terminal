package de.bsommerfeld.wsbg.terminal.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * D4 — the red gauge one stage upstream: does classifying the NEWS ITEM decide
 * red better than asking the compose model for a trigger?
 *
 * <p>The go/no-go measurement for moving the red decision out of the compose
 * call, run entirely offline against the archive — no pipeline change, no
 * production code path. Two parts:
 * <ul>
 *   <li><b>Classification</b>: every distinct {@code newsRefs} title in the
 *       archive through the {@code news-classify} prompt, in batches, with the
 *       class distribution as the result. {@code -Dbench.class.repeats=3}
 *       classifies each batch repeatedly and reports the disagreement share —
 *       the whole premise is that a one-of-fifteen token choice is stable where
 *       an abstract judgement is not.</li>
 *   <li><b>Red replay</b>: the proposed formula — a line goes red when an item
 *       it actually leaned on carries a red-capable class AND was fresh at
 *       publication time — computed over the archived lines and held against
 *       what production shipped.</li>
 * </ul>
 *
 * <p>Run: {@code mvn test -pl agent -Dtest=NewsClassifyBenchIT -Dtest.excludedGroups=
 * -Dbench.enabled=true -Dbench.model=<tag> -Dbench.ollama.url=http://localhost:11500}
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "bench.enabled", matches = "true")
class NewsClassifyBenchIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The closed class list — must match the prompt twins token for token. */
    private static final Set<String> CLASSES = Set.of(
            "UEBERNAHME", "INDEXAENDERUNG", "GROSSAUFTRAG", "ZULASSUNG",
            "PROGNOSE_HOCH", "PROGNOSE_RUNTER", "INSOLVENZ", "BILANZSKANDAL",
            "KAPITALERHOEHUNG", "HANDELSSTOPP",
            "ANALYSTENAKTION", "SPEKULATION", "RUECKBLICK", "MARKTBERICHT", "SONSTIGES");

    /** The classes that earn red on their own — the rubric's market movers, as code. */
    private static final Set<String> RED_CAPABLE = Set.of(
            "UEBERNAHME", "INDEXAENDERUNG", "GROSSAUFTRAG", "ZULASSUNG",
            "PROGNOSE_HOCH", "PROGNOSE_RUNTER", "INSOLVENZ", "BILANZSKANDAL",
            "KAPITALERHOEHUNG", "HANDELSSTOPP");

    /** Same window the brief uses to tag an item [STALE] (UnitBriefWriter). */
    private static final long STALE_AFTER_SECONDS = 36 * 3600L;

    private static final int BATCH = 10;

    @Test
    void classifyAndReplayRed() throws Exception {
        Path archive = Path.of(System.getProperty("bench.archive",
                System.getProperty("user.home")
                        + "/Library/Application Support/wsbg-terminal/archive/headlines.jsonl"));
        if (!Files.exists(archive)) {
            System.out.println("[BENCH:D4] archive missing at " + archive + " — skipped");
            return;
        }
        List<JsonNode> lines = readArchive(archive);

        // ---- corpus: every distinct news item the archive ever cited ----
        Map<String, String> titleByUrl = new LinkedHashMap<>();
        for (JsonNode e : lines) {
            for (JsonNode r : e.path("newsRefs")) {
                String url = r.path("url").asText("");
                String title = r.path("title").asText("");
                if (!url.isBlank() && !title.isBlank()) titleByUrl.putIfAbsent(url, title);
            }
        }
        System.out.printf(Locale.ROOT, "[BENCH:D4] corpus: %d distinct news item(s) over %d line(s)%n",
                titleByUrl.size(), lines.size());
        if (titleByUrl.isEmpty()) return;

        String model = System.getProperty("bench.model", "gemma4:e4b-mlx");
        String sys = PromptLoader.loadLocalized("news-classify", "de");
        int repeats = Integer.getInteger("bench.class.repeats", 1);

        List<String> urls = new ArrayList<>(titleByUrl.keySet());
        Map<String, String> classOf = new LinkedHashMap<>();
        // A verdict per URL is stable input for the replay half — cache it so a
        // formula change costs no model calls at all.
        Path cache = Path.of(System.getProperty("bench.class.cache", ""));
        if (!cache.toString().isBlank() && Files.exists(cache)) {
            JsonNode cached = JSON.readTree(Files.readString(cache, StandardCharsets.UTF_8));
            cached.fields().forEachRemaining(f -> {
                if (CLASSES.contains(f.getValue().asText("")) && titleByUrl.containsKey(f.getKey())) {
                    classOf.put(f.getKey(), f.getValue().asText());
                }
            });
            urls = urls.stream().filter(u -> !classOf.containsKey(u)).toList();
            System.out.printf(Locale.ROOT, "[BENCH:D4] cache: %d verdict(s) reused, %d to classify%n",
                    classOf.size(), urls.size());
        }
        Map<String, Set<String>> seenClasses = new LinkedHashMap<>();
        int unanswered = 0;
        for (int from = 0; from < urls.size(); from += BATCH) {
            List<String> slice = urls.subList(from, Math.min(from + BATCH, urls.size()));
            for (int pass = 0; pass < repeats; pass++) {
                Map<Integer, String> verdicts = classifyBatch(model, sys,
                        slice.stream().map(titleByUrl::get).toList());
                if (verdicts == null) {
                    System.out.println("[BENCH:D4] Ollama unreachable — skipped");
                    return;
                }
                for (int i = 0; i < slice.size(); i++) {
                    String v = verdicts.get(i + 1);
                    if (v == null) continue;
                    if (pass == 0) classOf.put(slice.get(i), v);
                    seenClasses.computeIfAbsent(slice.get(i), k -> new LinkedHashSet<>()).add(v);
                }
            }
            for (String u : slice) if (!classOf.containsKey(u)) unanswered++;
        }

        if (!cache.toString().isBlank()) {
            var node = JSON.createObjectNode();
            classOf.forEach(node::put);
            Files.writeString(cache, node.toPrettyString(), StandardCharsets.UTF_8);
        }

        Map<String, Integer> dist = new LinkedHashMap<>();
        classOf.values().forEach(c -> dist.merge(c, 1, Integer::sum));
        StringBuilder d = new StringBuilder();
        dist.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> d.append(e.getKey()).append(' ').append(e.getValue()).append("  "));
        long redCapable = classOf.values().stream().filter(RED_CAPABLE::contains).count();
        System.out.printf(Locale.ROOT,
                "[BENCH:D4] classified %d/%d (%d unanswered) | rot-fähig %d (%.1f%%) | model=%s%n",
                classOf.size(), titleByUrl.size(), unanswered, redCapable,
                100.0 * redCapable / Math.max(1, classOf.size()), model);
        System.out.println("    Klassen: " + d.toString().trim());
        if (repeats > 1) {
            long wobbled = seenClasses.values().stream().filter(s -> s.size() > 1).count();
            System.out.printf(Locale.ROOT, "    Stabilität über %d Durchläufe: %d von %d Items"
                    + " wechselten die Klasse (%.1f%%)%n", repeats, wobbled, seenClasses.size(),
                    100.0 * wobbled / Math.max(1, seenClasses.size()));
        }

        // ---- red replay, two formulas ----
        // A: class + freshness + the line leaned on the item (newsRefs IS that set —
        //    HeadlineWriter.buildNewsRefs keeps only the items the line wove in).
        // B: A, plus the item must NAME the subject. The archive shows why: a
        //    catalyst article can sit on the wrong unit (an Israeli tender for ONDS
        //    filed under RCAT, a Chinese order note under Kaspi.kz), and a formula
        //    that only asks "is there a catalyst nearby" inherits every one of those
        //    mis-attributions as a false red. Reuses the gilder's name test.
        int redA = 0;
        int redB = 0;
        int shippedRed = 0;
        System.out.println("[BENCH:D4] Zeilen, die rot würden (B = auch namentlich getroffen):");
        for (JsonNode e : lines) {
            if ("IMPORTANT".equals(e.path("highlight").asText(""))) shippedRed++;
            long createdAt = e.path("createdAt").asLong(0);
            String subject = e.path("subjects").isArray() && e.path("subjects").size() > 0
                    ? e.path("subjects").get(0).path("name").asText("") : "";
            String hit = null;
            boolean named = false;
            for (JsonNode r : e.path("newsRefs")) {
                String cls = classOf.get(r.path("url").asText(""));
                if (cls == null || !RED_CAPABLE.contains(cls)) continue;
                long published = r.path("publishedAt").asLong(0);
                if (published > 0 && createdAt - published > STALE_AFTER_SECONDS) continue;
                boolean namesSubject = !subject.isBlank()
                        && HeadlineGilder.displayFormIn(r.path("title").asText(""), subject) != null;
                if (hit == null || (!named && namesSubject)) {
                    hit = cls;
                    named = namesSubject;
                }
                if (named) break;
            }
            if (hit == null) continue;
            redA++;
            if (named) redB++;
            System.out.printf(Locale.ROOT, "    %s [%s] %s (Archiv: %s/%s)%n      %s%n",
                    named ? "A+B" : "A  ", hit, e.path("tickerSymbol").asText("-"),
                    e.path("trigger").asText("NONE"), e.path("highlight").asText("NORMAL"),
                    e.path("headline").asText(""));
        }
        System.out.printf(Locale.ROOT,
                "[BENCH:D4] Formel A: %d von %d rot (%.1f%%) | Formel B: %d (%.1f%%)"
                        + " | Produktion: %d (%.1f%%)%n",
                redA, lines.size(), 100.0 * redA / lines.size(),
                redB, 100.0 * redB / lines.size(),
                shippedRed, 100.0 * shippedRed / lines.size());
    }

    // ------------------------------------------------------------------ helpers

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

    /** One judge call over a batch; null when Ollama is unreachable. */
    private static Map<Integer, String> classifyBatch(String model, String sys, List<String> titles) {
        StringBuilder items = new StringBuilder("ITEMS:\n");
        for (int i = 0; i < titles.size(); i++) {
            items.append(i + 1).append(". ").append(titles.get(i)).append('\n');
        }
        String raw = ollama(model, sys, items.toString());
        if (raw == null) return null;
        Map<Integer, String> out = new LinkedHashMap<>();
        JsonNode root;
        try {
            root = JSON.readTree(raw);
        } catch (Exception e) {
            return out;
        }
        for (JsonNode entry : root.path("classes")) {
            int i = entry.path("i").asInt(-1);
            String token = entry.path("class").asText("").trim().toUpperCase(Locale.ROOT);
            if (i < 1 || i > titles.size() || !CLASSES.contains(token)) continue;
            out.putIfAbsent(i, token);
        }
        return out;
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(3)).build();

    private static String ollama(String model, String sys, String user) {
        try {
            var body = JSON.createObjectNode();
            body.put("model", model).put("stream", false).put("think", false);
            body.put("format", "json");
            var opts = body.putObject("options");
            opts.put("temperature", 0.0).put("top_p", 0.9).put("top_k", 40)
                    .put("num_predict", 2048);
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
}
