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
 *       an abstract judgement is not. MEASURED 2026-08-18 on
 *       nemotron-3.5-lightning:30b-mlx: the premise does NOT hold there — 8 of 82
 *       items (9.8%) changed class over three passes, and the red count below
 *       swung between 2 and 4 across runs of the IDENTICAL prompt. Any formula
 *       resting on these verdicts inherits that swing, so a single run of this
 *       stand is a draw from a wide distribution, not a result.</li>
 *   <li><b>Red replay</b>: three formulas over the archived lines, held against
 *       what production shipped. <b>A</b> — a line goes red when an item it
 *       actually leaned on carries a red-capable class AND was fresh at
 *       publication time. <b>B</b> — A, plus the item's title NAMES the subject.
 *       <b>C</b> — A, plus the subject IS the actor the classifier named. B and C
 *       attack the same failure, a catalyst article filed under the wrong unit;
 *       C is the sharper test, because a sector wrap names many companies but
 *       the event it reports has one owner. <b>D</b> — C, plus one red per story:
 *       a catalyst fires once instead of re-reddening every follow-up line while
 *       the article stays fresh.</li>
 * </ul>
 *
 * <p>Run: {@code mvn test -pl agent -Dtest=NewsClassifyBenchIT -Dtest.excludedGroups=
 * -Dbench.enabled=true -Dbench.model=<tag> -Dbench.ollama.url=http://localhost:11500}
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "bench.enabled", matches = "true")
class NewsClassifyBenchIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Model calls this run — evidences the arm: at one item per call it equals the corpus. */
    private static int calls = 0;

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

    /**
     * Items per classify call — the UNBUNDLING arm's variable
     * ({@code -Dbench.class.batch=1} asks one title per call).
     *
     * <p>This is the experiment the 9.8% churn figure could not settle: that number
     * was measured at ten titles AND ten actors per call, so it never showed whether
     * a one-of-fifteen choice is unstable or whether twenty decisions in one reply
     * are. Same prompt, same corpus, same passes — only this number differs.
     */
    private static int batchSize() {
        return Math.max(1, Integer.getInteger("bench.class.batch", 10));
    }

    /** Attempts per batch before its items are given up as unanswered (see the loop). */
    private static final int PARSE_ATTEMPTS = 3;

    /** Cache slot holding the prompt fingerprint the verdicts were produced under. */
    private static final String PROMPT_KEY = "__prompt";

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
        System.out.printf(Locale.ROOT, "[BENCH:D4] corpus: %d distinct news item(s) over %d line(s)"
                + " | %d item(s) per call%n", titleByUrl.size(), lines.size(), batchSize());
        if (titleByUrl.isEmpty()) return;

        String model = System.getProperty("bench.model", "gemma4:e4b-mlx");
        String sys = PromptLoader.loadLocalized("news-classify", "de");
        int repeats = Integer.getInteger("bench.class.repeats", 1);

        List<String> urls = new ArrayList<>(titleByUrl.keySet());
        Map<String, String> classOf = new LinkedHashMap<>();
        // A verdict per URL is stable input for the replay half — cache it so a
        // formula change costs no model calls at all.
        Path cache = Path.of(System.getProperty("bench.class.cache", ""));
        // Verdicts are only comparable to the prompt that produced them. Without
        // this the obvious workflow — sharpen a class, re-run — silently replays
        // the OLD verdicts and reports the change as having no effect.
        // Batch size changes the verdicts, so it belongs in the fingerprint next to
        // the prompt — otherwise the unbundled arm silently replays the bundled one.
        String promptTag = Integer.toHexString(sys.hashCode()) + "/b" + batchSize();
        if (!cache.toString().isBlank() && Files.exists(cache)) {
            JsonNode cached = JSON.readTree(Files.readString(cache, StandardCharsets.UTF_8));
            if (!promptTag.equals(cached.path(PROMPT_KEY).asText(""))) {
                System.out.println("[BENCH:D4] cache was written under a different prompt"
                        + " or batch size — discarded, everything re-classifies");
                cached = JSON.createObjectNode();
            }
            cached.fields().forEachRemaining(f -> {
                // A verdict without the separator predates the actor field — dropping
                // it re-classifies that item instead of handing formula C a blank actor.
                String v = f.getValue().asText("");
                if (!PROMPT_KEY.equals(f.getKey()) && RedReplay.Verdict.isCurrentWireForm(v)
                        && CLASSES.contains(cls(v))
                        && titleByUrl.containsKey(f.getKey())) {
                    classOf.put(f.getKey(), v);
                }
            });
            urls = urls.stream().filter(u -> !classOf.containsKey(u)).toList();
            System.out.printf(Locale.ROOT, "[BENCH:D4] cache: %d verdict(s) reused, %d to classify%n",
                    classOf.size(), urls.size());
        }
        Map<String, Set<String>> seenClasses = new LinkedHashMap<>();
        Map<String, Set<String>> seenActors = new LinkedHashMap<>();
        int unanswered = 0;
        int batch = batchSize();
        for (int from = 0; from < urls.size(); from += batch) {
            List<String> slice = urls.subList(from, Math.min(from + batch, urls.size()));
            for (int pass = 0; pass < repeats; pass++) {
                // A malformed reply is retried, not shrugged off. The MLX runner is
                // not bit-deterministic even at temperature 0, so a batch can come
                // back as unparseable JSON once and cleanly the next time — measured:
                // one batch of ten near-identical titles failed in a run and parsed
                // fine on replay. Without the retry those ten items leave the corpus
                // silently and every red count below is understated by whatever sat
                // in them.
                Map<Integer, String> verdicts = null;
                for (int attempt = 1; attempt <= PARSE_ATTEMPTS; attempt++) {
                    verdicts = classifyBatch(model, sys,
                            slice.stream().map(titleByUrl::get).toList());
                    if (verdicts == null) {
                        System.out.println("[BENCH:D4] Ollama unreachable — skipped");
                        return;
                    }
                    if (!verdicts.isEmpty()) break;
                    System.out.printf(Locale.ROOT, "    [!] items %d-%d: attempt %d of %d"
                            + " yielded no verdict%n", from + 1, from + slice.size(),
                            attempt, PARSE_ATTEMPTS);
                }
                for (int i = 0; i < slice.size(); i++) {
                    String v = verdicts.get(i + 1);
                    if (v == null) continue;
                    if (pass == 0) classOf.put(slice.get(i), v);
                    seenClasses.computeIfAbsent(slice.get(i), k -> new LinkedHashSet<>()).add(cls(v));
                    seenActors.computeIfAbsent(slice.get(i), k -> new LinkedHashSet<>())
                            .add(actor(v).toLowerCase(Locale.ROOT));
                }
            }
            for (String u : slice) if (!classOf.containsKey(u)) unanswered++;
        }

        if (!cache.toString().isBlank()) {
            var node = JSON.createObjectNode();
            node.put(PROMPT_KEY, promptTag);
            classOf.forEach(node::put);
            Files.writeString(cache, node.toPrettyString(), StandardCharsets.UTF_8);
        }

        Map<String, Integer> dist = new LinkedHashMap<>();
        classOf.values().forEach(c -> dist.merge(cls(c), 1, Integer::sum));
        StringBuilder d = new StringBuilder();
        dist.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> d.append(e.getKey()).append(' ').append(e.getValue()).append("  "));
        long redCapable = classOf.values().stream().map(NewsClassifyBenchIT::cls)
                .filter(RED_CAPABLE::contains).count();
        System.out.printf(Locale.ROOT,
                "[BENCH:D4] classified %d/%d (%d unanswered) in %d call(s) à %d item(s)"
                        + " | rot-fähig %d (%.1f%%) | model=%s%n",
                classOf.size(), titleByUrl.size(), unanswered, calls, batchSize(), redCapable,
                100.0 * redCapable / Math.max(1, classOf.size()), model);
        System.out.println("    Klassen: " + d.toString().trim());
        if (repeats > 1) {
            long wobbled = seenClasses.values().stream().filter(s -> s.size() > 1).count();
            long actorWobbled = seenActors.values().stream().filter(s -> s.size() > 1).count();
            System.out.printf(Locale.ROOT, "    Stabilität über %d Durchläufe: %d von %d Items"
                    + " wechselten die Klasse (%.1f%%), %d den Akteur (%.1f%%)%n",
                    repeats, wobbled, seenClasses.size(),
                    100.0 * wobbled / Math.max(1, seenClasses.size()),
                    actorWobbled, 100.0 * actorWobbled / Math.max(1, seenActors.size()));
        }

        // ---- red replay, three formulas ----
        // A: class + freshness + the line leaned on the item (newsRefs IS that set —
        //    HeadlineWriter.buildNewsRefs keeps only the items the line wove in).
        // B: A, plus the item must NAME the subject. The archive shows why: a
        //    catalyst article can sit on the wrong unit (an Israeli tender for ONDS
        //    filed under RCAT, a Chinese order note under Kaspi.kz), and a formula
        //    that only asks "is there a catalyst nearby" inherits every one of those
        //    mis-attributions as a false red. Reuses the gilder's name test.
        // C: A, plus the subject must BE the actor the classifier named — the
        //    sharper form of the same test, since a title can name a company the
        //    event is not about at all.
        // D: C, plus a catalyst fires ONCE. C alone re-reds every follow-up line of
        //    the same story for as long as the article stays fresh (36 h), which is
        //    the archive's IREN case: two lines, one Microsoft order. "Rot ist selten"
        //    does not survive a red that repeats itself for a day and a half, so the
        //    subject is capped at one red per staleness window.
        int redA = 0;
        int redB = 0;
        int redC = 0;
        int redD = 0;
        int shippedRed = 0;
        // subject -> createdAt of the red it last earned, for formula D.
        Map<String, Long> lastRedAt = new LinkedHashMap<>();
        System.out.println("[BENCH:D4] Zeilen, die rot würden"
                + " (B = Titel nennt das Subjekt, C = das Subjekt IST der Akteur):");
        for (JsonNode e : lines) {
            if ("IMPORTANT".equals(e.path("highlight").asText(""))) shippedRed++;
            long createdAt = e.path("createdAt").asLong(0);
            String subject = e.path("subjects").isArray() && e.path("subjects").size() > 0
                    ? e.path("subjects").get(0).path("name").asText("") : "";
            List<RedReplay.Evidence> evidence = new ArrayList<>();
            for (JsonNode r : e.path("newsRefs")) {
                String verdict = classOf.get(r.path("url").asText(""));
                if (verdict == null) continue;
                evidence.add(new RedReplay.Evidence(RedReplay.Verdict.decode(verdict),
                        r.path("title").asText(""), r.path("publishedAt").asLong(0)));
            }
            RedReplay.Hit best = RedReplay.bestHit(subject, createdAt, evidence,
                    RED_CAPABLE, STALE_AFTER_SECONDS);
            if (!best.fired()) continue;
            String hit = best.cls();
            String hitActor = best.actor();
            boolean named = best.named();
            boolean actorHit = best.isActor();
            redA++;
            if (named) redB++;
            if (actorHit) redC++;
            boolean repeat = false;
            if (actorHit) {
                repeat = RedReplay.isRepeat(lastRedAt.get(subject), createdAt,
                        STALE_AFTER_SECONDS);
                if (!repeat) {
                    lastRedAt.put(subject, createdAt);
                    redD++;
                }
            }
            System.out.printf(Locale.ROOT, "    %s [%s%s] %s (Archiv: %s/%s)%n      %s%n",
                    "A" + (named ? "+B" : "  ") + (actorHit ? (repeat ? "+C·wdh" : "+C") : "  "), hit,
                    hitActor.isBlank() ? "" : " von " + hitActor,
                    e.path("tickerSymbol").asText("-"),
                    e.path("trigger").asText("NONE"), e.path("highlight").asText("NORMAL"),
                    e.path("headline").asText(""));
        }
        System.out.printf(Locale.ROOT,
                "[BENCH:D4] Formel A: %d von %d rot (%.1f%%) | B (Name): %d (%.1f%%)"
                        + " | C (Akteur): %d (%.1f%%) | D (C, einmal je Story): %d (%.1f%%)"
                        + " | Produktion: %d (%.1f%%)%n",
                redA, lines.size(), 100.0 * redA / lines.size(),
                redB, 100.0 * redB / lines.size(),
                redC, 100.0 * redC / lines.size(),
                redD, 100.0 * redD / lines.size(),
                shippedRed, 100.0 * shippedRed / lines.size());
    }

    // ------------------------------------------------------------------ helpers

    /** @see RedReplay.Verdict — the tested implementation, this only names it here. */
    private static String cls(String verdict) {
        return RedReplay.Verdict.decode(verdict).cls();
    }

    private static String actor(String verdict) {
        return RedReplay.Verdict.decode(verdict).actor();
    }

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

    /**
     * One judge call over a batch; null when Ollama is unreachable.
     *
     * <p>Both reply shapes are accepted. The prompt asks for {@code {"classes":[…]}},
     * but the MLX runner ignores the {@code format} grammar (see ComposeReplyParser),
     * so the model is free to answer with the bare array — and did, the moment the
     * actor field was added to the prompt. Insisting on the wrapper made every batch
     * parse to nothing and the whole stand report a silent 0 of 102 classified, which
     * reads exactly like "the idea does not work". An instrument may not fail quietly:
     * a reply that yields no verdict at all is printed, raw.
     */
    private static Map<Integer, String> classifyBatch(String model, String sys, List<String> titles) {
        StringBuilder items = new StringBuilder("ITEMS:\n");
        for (int i = 0; i < titles.size(); i++) {
            items.append(i + 1).append(". ").append(titles.get(i)).append('\n');
        }
        calls++;
        String raw = ollama(model, sys, items.toString());
        if (raw == null) return null;
        Map<Integer, String> out = new LinkedHashMap<>();
        JsonNode root;
        try {
            root = JSON.readTree(raw);
        } catch (Exception e) {
            System.out.println("    [!] batch reply was not JSON (" + raw.length()
                    + " chars, " + e + "): " + raw.replace('\n', ' '));
            return out;
        }
        RedReplay.parseReply(root, titles.size(), CLASSES)
                .forEach((i, v) -> out.put(i, v.encode()));
        if (out.isEmpty()) {
            System.out.println("    [!] batch parsed to no verdict (" + raw.length()
                    + " chars): " + raw.replace('\n', ' '));
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
