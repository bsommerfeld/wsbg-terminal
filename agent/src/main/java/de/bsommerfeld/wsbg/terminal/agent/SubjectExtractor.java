package de.bsommerfeld.wsbg.terminal.agent;

import com.fasterxml.jackson.databind.JsonNode;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage 1 — extraction: turns a cluster brief + cluster into a list of
 * market-relevant subject names (uncapped, primary first) and the model's own
 * event-cut primary pick. Owns the single-vs-chunked branching, the num_ctx
 * overflow guard, the malformed-reply salvage, the price-tail/spaced-ticker name
 * cleaning, and the shared related-lookup budget distribution. Extracted verbatim
 * from {@link EditorialAgent}.
 */
final class SubjectExtractor {

    private static final Logger LOG = LoggerFactory.getLogger(SubjectExtractor.class);

    /**
     * When a cluster carries more than this many comments, subject extraction is
     * run in batches of this size and the names unioned; no comment is dropped
     * (every batch is extracted, names deduped). This is a num_ctx overflow guard
     * for monster threads now, NOT a degeneration guard: the old tight thresholds
     * (25 comments / 2 800 chars) dated from the hidden-thinking era, where the
     * invisible reasoning ate the token budget and long briefs came back
     * blank/truncated. With think=false the model enumerates a 48-name watchlist
     * from a single 12k-char brief cleanly (verified 2026-07-01) — which also
     * fixed the "under-extraction of long ticker lists" quality gap that the
     * chunking itself was causing.
     */
    private static final int EXTRACT_CHUNK_SIZE = 80;

    /**
     * A single extraction call over a brief larger than this is chunked even if the
     * comment COUNT is small — purely to stay well inside num_ctx (12 000 chars
     * ≈ 3k tokens + ~800 system + 768 reply ≪ 8192). Validated live at this size.
     */
    private static final int EXTRACT_CHAR_BUDGET = 12_000;

    /** Max characters of COMMENT text per extraction chunk (the shared preamble rides on top). */
    private static final int EXTRACT_CHUNK_CHARS = 9_000;

    private final AgentBrain brain;
    private final RedditRepository redditRepository;
    private final ChatGateway chatGateway;

    SubjectExtractor(AgentBrain brain, RedditRepository redditRepository, ChatGateway chatGateway) {
        this.brain = brain;
        this.redditRepository = redditRepository;
        this.chatGateway = chatGateway;
    }

    /**
     * Stage-1 output: the named subjects (uncapped, primary first), the model's
     * primary pick ({@code ""} when the reply carried none — legacy shape or no
     * market subject), their count, and the raw reply.
     */
    record Subjects(List<String> names, String primaryName, int namedByModel, String raw) {}

    Subjects extract(ChatModel model, InvestigationCluster cluster, String brief) {
        String sys = PromptLoader.loadLocalized("subject-extraction", brain.getUserLanguage().code())
                .replace("{{ENTITY_ALIASES}}", WsbgJargon.entityAliasesForPrompt());

        int comments = countComments(cluster);
        if (comments <= EXTRACT_CHUNK_SIZE && brief.length() <= EXTRACT_CHAR_BUDGET) {
            // Common path: one call over the full rich brief (vision, poll,
            // covered-split all intact) — only when it's small enough to be safe.
            String text = extractChat(model, sys, brief);
            Extraction ex = parseExtraction(text);
            List<String> out = dedupClean(ex.names());
            String primary = cleanSubjectName(ex.primary());
            if (out.isEmpty()) {
                String raw = text == null ? "" : text.strip();
                LOG.warn("[EXTRACT] 0 subjects — brief={} chars (~{} tok), system={} chars; raw reply: {}",
                        brief.length(), brief.length() / 4, sys.length(),
                        raw.length() > 400 ? raw.substring(0, 400) + "…" : raw);
            }
            return new Subjects(out, primary, out.size(), text);
        }

        // Many comments → batch the extraction so each output array stays short
        // and reliable, then union the names. No comment is dropped. Each batch
        // votes its own primary; the most-voted name wins (first-seen on a tie) —
        // a monster thread is usually a container anyway, and the attribution
        // heuristic (initial title) still backstops a bad vote.
        List<String> chunks = commentChunks(cluster, EXTRACT_CHUNK_SIZE);
        Map<String, String> union = new LinkedHashMap<>(); // lower-case key → first-seen spelling
        Map<String, Integer> primaryVotes = new LinkedHashMap<>();
        for (String chunk : chunks) {
            String text = extractChat(model, sys, chunk);
            Extraction ex = parseExtraction(text);
            for (String name : ex.names()) {
                String clean = cleanSubjectName(name);
                if (!clean.isEmpty()) union.putIfAbsent(clean.toLowerCase(Locale.ROOT), clean);
            }
            String p = cleanSubjectName(ex.primary());
            if (!p.isEmpty()) primaryVotes.merge(p.toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        String primary = primaryVotes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> union.getOrDefault(e.getKey(), e.getKey()))
                .orElse("");
        LOG.info("[EXTRACT] chunked {} comments into {} batch(es) → {} unique subject(s), primary '{}'",
                comments, chunks.size(), union.size(), primary);
        List<String> names = new ArrayList<>(union.values());
        return new Subjects(names, primary, names.size(), "<chunked: " + chunks.size() + " batch(es)>");
    }

    /**
     * One extraction model call, with a single retry on a degenerate BLANK reply. The 4B
     * model occasionally returns an empty string in JSON mode even on a small input (a random
     * degeneration, not a real "no subjects" — that comes back as {@code {"subjects":[]}}).
     * Ollama uses a fresh random seed per call, so the retry genuinely varies. A legit empty
     * array is returned as-is (no retry); only a truly blank reply is retried.
     */
    private String extractChat(ChatModel model, String sys, String input) {
        String text = chatGateway.chat(model, sys, input);
        if (text == null || text.strip().isEmpty()) {
            text = chatGateway.chat(model, sys, input);
        }
        return text;
    }

    /**
     * A transcribed price/move tail glued onto a subject name — a decimal number
     * (1.234 or 1,23), a currency/percent symbol, or a trend arrow, and everything
     * after it. Plain integers ("S&P 500", "3M") are NOT matched, so numeric names
     * survive.
     */
    private static final Pattern PRICE_TAIL =
            Pattern.compile("\\s*(?:\\d+[.,]\\d|[€$£%]|▲|▼|↑|↓).*$");

    /**
     * Spaced-out all-caps ticker, e.g. an OCR'd watchlist row read as "O T L K".
     * A run of ≥3 single capitals separated only by spaces is collapsed to one
     * token ("O T L K" → "OTLK") so it resolves as the ticker it is. Bounded to
     * single letters so ordinary multi-word names ("Take Two") are untouched.
     */
    private static final Pattern SPACED_TICKER =
            Pattern.compile("\\b([A-Z](?:\\s+[A-Z]){2,5})\\b");

    static String cleanSubjectName(String name) {
        if (name == null) return "";
        String cut = PRICE_TAIL.matcher(name.strip()).replaceFirst("").strip();
        if (cut.isEmpty()) cut = name.strip();
        Matcher m = SPACED_TICKER.matcher(cut);
        if (m.find()) {
            cut = m.replaceAll(mr -> mr.group(1).replaceAll("\\s+", ""));
        }
        return cut;
    }

    /** Cleans each name and dedups case-insensitively, keeping first-seen spelling + order. */
    private static List<String> dedupClean(List<String> names) {
        Map<String, String> seen = new LinkedHashMap<>();
        for (String n : names) {
            String c = cleanSubjectName(n);
            if (!c.isEmpty()) seen.putIfAbsent(c.toLowerCase(Locale.ROOT), c);
        }
        return new ArrayList<>(seen.values());
    }

    /**
     * The model's event cut out of one extraction reply: the {@code primary}
     * (protagonist — the entity the headline will be about, tradeable or not) and
     * {@code names} = primary first + every related subject. Both legacy shapes
     * still parse: a bare {@code {"subjects":[…]}} yields an empty primary (the
     * attribution heuristic then decides).
     */
    record Extraction(String primary, List<String> names) {
        static final Extraction EMPTY = new Extraction("", List.of());
    }

    /** Strict parse of the extraction reply, with a salvage pass for a broken/truncated reply. */
    private Extraction parseExtraction(String text) {
        String primary = "";
        List<String> out = new ArrayList<>();
        JsonNode root = JsonReplies.parseJson(text);
        if (root != null) {
            primary = root.path("primary").asText("").trim();
            if (!primary.isEmpty()) out.add(primary);
            for (JsonNode s : root.path("related")) {
                String name = s.asText("").trim();
                if (!name.isEmpty()) out.add(name);
            }
            // Legacy shape ({"subjects":[…]}) — pre-primary replies and old snapshots.
            for (JsonNode s : root.path("subjects")) {
                String name = s.asText("").trim();
                if (!name.isEmpty()) out.add(name);
            }
        }
        if (out.isEmpty()) {
            // The 4B model occasionally emits a malformed/truncated array (long
            // arrays are where it degenerates) → recover whatever names came
            // through intact rather than losing the whole batch.
            String salvagedPrimary = JsonReplies.regexStringField(text, "primary");
            if (salvagedPrimary != null && !salvagedPrimary.isBlank()) {
                primary = salvagedPrimary.trim();
                out.add(primary);
            }
            List<String> salvaged = new ArrayList<>(salvageArrayNames(text, "related"));
            salvaged.addAll(salvageArrayNames(text, "subjects"));
            if (!salvaged.isEmpty()) {
                LOG.warn("[EXTRACT] strict parse failed, salvaged {} subject name(s)", salvaged.size());
                out.addAll(salvaged);
            }
        }
        return out.isEmpty() ? Extraction.EMPTY : new Extraction(primary, out);
    }

    private int countComments(InvestigationCluster cluster) {
        int n = 0;
        for (String tid : cluster.activeThreadIds) {
            n += redditRepository.getCommentsForThread(tid, 0).size();
        }
        return n;
    }

    /**
     * Splits the cluster's comments into batches of {@code perChunk}, each prefixed
     * with the same thread-title/body preamble so every batch carries enough
     * context to name subjects. Comment-derived only (the rich brief's vision/poll
     * niceties matter far less on a thread big enough to need batching, which is by
     * definition a comment-heavy text thread).
     */
    private List<String> commentChunks(InvestigationCluster cluster, int perChunk) {
        StringBuilder preamble = new StringBuilder("THREADS IN THIS CLUSTER:\n");
        List<String> lines = new ArrayList<>();
        for (String tid : cluster.activeThreadIds) {
            RedditThread t = redditRepository.getThread(tid);
            if (t == null) continue;
            preamble.append("- ").append(oneLine(t.title()));
            if (t.textContent() != null && !t.textContent().isBlank()) {
                preamble.append(" — ").append(oneLine(t.textContent()));
            }
            preamble.append('\n');
            // Image transcripts are normal context too — fold the thread's + its
            // comments' cached vision into the preamble so screenshot-only subjects
            // (portfolio holdings, watchlists, memes) get named in extraction.
            String vis = threadVision(t, tid);
            if (!vis.isEmpty()) {
                preamble.append("  [images]: ").append(oneLine(vis)).append('\n');
            }
            for (RedditComment c : redditRepository.getCommentsForThread(tid, 0)) {
                if (c.body() == null || c.body().isBlank()) continue;
                lines.add("- " + oneLine(c.body()));
            }
        }
        List<String> chunks = new ArrayList<>();
        if (lines.isEmpty()) {
            chunks.add(preamble.toString());
            return chunks;
        }
        int i = 0;
        while (i < lines.size()) {
            StringBuilder sb = new StringBuilder(preamble);
            sb.append("\nCOMMENTS (batch ").append(chunks.size() + 1).append("):\n");
            // Stop a batch at perChunk lines OR the char budget, whichever comes first —
            // a few long comments must not balloon one batch back into the degenerate zone.
            // `count == 0` forces at least one line so the loop always advances.
            int count = 0, commentChars = 0;
            while (i < lines.size() && count < perChunk
                    && (count == 0 || commentChars < EXTRACT_CHUNK_CHARS)) {
                String line = lines.get(i);
                sb.append(line).append('\n');
                commentChars += line.length() + 1;
                i++;
                count++;
            }
            chunks.add(sb.toString());
        }
        return chunks;
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').strip();
    }

    /** Joined cached vision transcripts for a thread's images + its comments' images (cache-only). */
    private String threadVision(RedditThread t, String threadId) {
        StringBuilder sb = new StringBuilder();
        appendVision(sb, t.imageUrls());
        for (RedditComment c : redditRepository.getCommentsForThread(threadId, 0)) {
            appendVision(sb, c.imageUrls());
        }
        return sb.toString().strip();
    }

    private void appendVision(StringBuilder sb, List<String> urls) {
        if (urls == null) return;
        for (String url : urls) {
            String d = brain.describeImageIfCached(url);
            if (d != null && !d.isBlank()) sb.append(d).append('\n');
        }
    }

    /**
     * Recovers subject names from a reply whose JSON is broken/truncated: finds the
     * {@code "subjects"} key, takes the array body (to the closing {@code ]} or — if
     * the reply was cut off — to the end), and pulls every complete quoted string.
     * A truncated final entry (no closing quote) is simply skipped, so we keep
     * whatever names came through intact rather than losing all of them.
     * Package-private for testing.
     */
    static List<String> salvageSubjectNames(String text) {
        return salvageArrayNames(text, "subjects");
    }

    /** Pulls every complete quoted string out of the (possibly truncated) array under {@code key}. */
    static List<String> salvageArrayNames(String text, String key) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        int at = text.indexOf("\"" + key + "\"");
        if (at < 0) return out;
        int lb = text.indexOf('[', at);
        if (lb < 0) return out;
        int rb = text.indexOf(']', lb);
        String arr = rb < 0 ? text.substring(lb) : text.substring(lb, rb);
        Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(arr);
        while (m.find()) {
            String name = m.group(1).trim();
            if (!name.isEmpty()) out.add(name);
        }
        return out;
    }

    /**
     * Spreads a shared pool of {@code budget} related-instrument lookups evenly
     * across {@code n} subjects — round-robin: everyone gets 1 before anyone
     * gets a 2nd — capped at {@code perSubject} each. So 24 over 24 subjects = 1
     * each; over 6 = 4 each; over 25 the 25th gets 0.
     */
    static int[] distributeRelated(int n, int budget, int perSubject) {
        int[] alloc = new int[Math.max(0, n)];
        int remaining = budget;
        boolean progress = true;
        while (remaining > 0 && progress) {
            progress = false;
            for (int i = 0; i < alloc.length && remaining > 0; i++) {
                if (alloc[i] < perSubject) {
                    alloc[i]++;
                    remaining--;
                    progress = true;
                }
            }
        }
        return alloc;
    }
}
