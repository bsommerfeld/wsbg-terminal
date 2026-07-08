package de.bsommerfeld.wsbg.terminal.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsommerfeld.wsbg.terminal.agent.HeadlineWriter.Draft;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns one raw compose reply into a {@link Draft} plus the {@code newsUsed}/
 * {@code derivedFrom} ordinal lists, with the strict-parse → salvage cascade and
 * the redundant-empty determination. Extracted verbatim from {@link EditorialAgent}.
 */
final class ComposeReplyParser {

    private ComposeReplyParser() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The result of parsing one compose reply. {@code draft} is null when no usable
     * headline came through; {@code redundant} is the model's deliberate empty against
     * the unit's own priors (never for a first line); {@code salvaged} means the strict
     * parse failed and a balanced-object recovery found the headline.
     */
    record ParsedCompose(Draft draft, boolean salvaged, boolean redundant,
            List<Integer> newsUsed, List<Integer> derivedFrom) {}

    /**
     * Strict parse of one compose reply, falling back to the balanced-object salvage.
     * {@code hasPriors} decides whether an explicit empty headline counts as a
     * deliberate redundant (only against the unit's OWN priors — a first line is never
     * dropped).
     */
    static ParsedCompose parse(String text, boolean hasPriors) {
        Draft draft = null;
        boolean salvaged = false;
        boolean redundant = false;
        List<Integer> citedNews = List.of();
        List<Integer> derivedFrom = List.of();
        JsonNode obj = JsonReplies.parseJson(text);
        if (obj != null && obj.has("headline")) {
            draft = toDraft(obj);
            citedNews = JsonReplies.readInts(obj.path("newsUsed"));
            derivedFrom = JsonReplies.readInts(obj.path("derivedFrom"));
            // Empty headline is redundant ONLY against the unit's own priors — NEVER for
            // a first line. Otherwise an empty reply is a model lapse, not "nothing to say".
            redundant = draft == null && hasPriors;
        }
        if (draft == null && !redundant) {
            for (JsonNode o : salvageObjects(text)) {
                Draft d = toDraft(o);
                if (d != null) {
                    draft = d;
                    citedNews = JsonReplies.readInts(o.path("newsUsed"));
                    derivedFrom = JsonReplies.readInts(o.path("derivedFrom"));
                    salvaged = true;
                    break;
                }
            }
        }
        return new ParsedCompose(draft, salvaged, redundant, citedNews, derivedFrom);
    }

    /**
     * Best-effort recovery: parses every <em>balanced</em> top-level
     * {@code {...}} in the reply independently, skipping any that fail (e.g. a
     * truncated tail). String-aware (ignores braces inside quotes) and
     * brace-depth-aware (handles nested {@code subjects} objects). Used when the
     * strict parse rejected the whole reply.
     */
    private static List<JsonNode> salvageObjects(String text) {
        List<JsonNode> out = new ArrayList<>();
        if (text == null) return out;
        int from = text.indexOf('{');
        if (from < 0) return out;

        int depth = 0;
        int objStart = -1;
        boolean inStr = false;
        boolean esc = false;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            switch (c) {
                case '"' -> inStr = true;
                case '{' -> { if (depth == 0) objStart = i; depth++; }
                case '}' -> {
                    if (depth > 0 && --depth == 0 && objStart >= 0) {
                        try {
                            out.add(JSON.readTree(text.substring(objStart, i + 1)));
                        } catch (Exception ignored) {
                            // incomplete/garbled object — skip just this one
                        }
                        objStart = -1;
                    }
                }
                default -> { /* ignore */ }
            }
        }
        return out;
    }

    private static Draft toDraft(JsonNode h) {
        String headline = h.path("headline").asText("").trim();
        if (headline.isEmpty()) return null;
        return new Draft(
                headline,
                h.path("sentiment").asText(""),
                h.path("highlight").asText(""),
                h.path("trigger").asText(""));
    }

}
