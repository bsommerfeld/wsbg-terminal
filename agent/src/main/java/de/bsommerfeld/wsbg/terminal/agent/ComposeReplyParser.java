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
 *
 * <p>This class is where the compose contract is actually ENFORCED. The JSON schema
 * sent as {@code format} is honoured only by the GGUF runner; the MLX runner — the
 * Apple-Silicon default — ignores it entirely (verified live 2026-08-13), so the
 * closed enums and the object shape are a prompt-side request there. The cascade is:
 * strict parse → loose-JSON sanitize → balanced-object salvage, plus
 * {@link #schemaViolations} so the caller can hold the concrete deviation up to the
 * model ONCE (one corrective retry) before falling back to the defensive enum
 * normalisation downstream ({@link HighlightReconciler}, {@code HeadlineSentiment}).
 *
 * <p>Stateless and thread-safe: compose runs on two worker threads concurrently.
 */
final class ComposeReplyParser {

    private ComposeReplyParser() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The result of parsing one compose reply. {@code draft} is null when no usable
     * headline came through; {@code redundant} is the model's deliberate empty against
     * the unit's own priors (never for a first line); {@code salvaged} means the strict
     * parse failed and a balanced-object recovery found the headline;
     * {@code schemaViolations} names every material deviation from the compose
     * contract (unknown enum values — empty when the reply conforms).
     */
    record ParsedCompose(Draft draft, boolean salvaged, boolean redundant,
            List<Integer> newsUsed, List<Integer> derivedFrom, List<String> schemaViolations) {}

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
        // Second chance for the un-enforced runners: the MLX build ignores the
        // response schema entirely (verified live 2026-08-13), so replies arrive
        // with bare [N2] tokens and stray parens that break strict JSON. A
        // string-aware sanitize pass recovers those before the object salvage.
        if (obj == null) obj = JsonReplies.parseJson(sanitizeLooseJson(text));
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
        return new ParsedCompose(draft, salvaged, redundant, citedNews, derivedFrom,
                draft == null ? List.of() : schemaViolations(draft));
    }

    /**
     * Validates one usable draft against the closed compose vocabulary
     * ({@link OllamaModelFactory#TRIGGER_VALUES} etc. — the same lists the schema
     * carries), case/whitespace-normalised: a runner that enforces the grammar can
     * never land here with a violation, a runner that ignores it (MLX) can. The
     * returned names are meant to be quoted back at the model in the one corrective
     * retry. Package-private for testing.
     */
    static List<String> schemaViolations(Draft draft) {
        List<String> out = new ArrayList<>();
        if (!OllamaModelFactory.TRIGGER_VALUES.contains(normalize(draft.trigger()))) {
            out.add("trigger \"" + draft.trigger() + "\" not one of " + OllamaModelFactory.TRIGGER_VALUES);
        }
        if (!OllamaModelFactory.HIGHLIGHT_VALUES.contains(normalize(draft.highlight()))) {
            out.add("highlight \"" + draft.highlight() + "\" not one of " + OllamaModelFactory.HIGHLIGHT_VALUES);
        }
        if (!OllamaModelFactory.SENTIMENT_VALUES.contains(normalize(draft.sentiment()))) {
            out.add("sentiment \"" + draft.sentiment() + "\" not one of " + OllamaModelFactory.SENTIMENT_VALUES);
        }
        return out;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toUpperCase(java.util.Locale.ROOT);
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

    /**
     * String-aware cleanup of the two loose-JSON shapes the schema-less runners
     * emit inside otherwise-valid replies: a bare news ordinal as an unquoted array
     * token ({@code "newsUsed": [N2]}) and stray parentheses outside any string
     * ({@code ["[N2]")]}). Text INSIDE quoted strings is untouched — a headline
     * mentioning "[N2]" or parens survives verbatim. Package-private for testing.
     */
    static String sanitizeLooseJson(String text) {
        if (text == null) return null;
        StringBuilder out = new StringBuilder(text.length());
        boolean inStr = false;
        boolean esc = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inStr) {
                out.append(c);
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') {
                inStr = true;
                out.append(c);
                continue;
            }
            if (c == '(' || c == ')') continue; // stray parens outside strings
            if (c == 'N' && i + 1 < text.length() && Character.isDigit(text.charAt(i + 1))
                    && (i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1)))) {
                continue; // bare ordinal token N2 → 2
            }
            out.append(c);
        }
        return out.toString();
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
