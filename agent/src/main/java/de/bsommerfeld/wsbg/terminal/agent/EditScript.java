package de.bsommerfeld.wsbg.terminal.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The KI-DD's surgical edit protocol (user mandate 2026-07-13 "'XYZ' ersetzen
 * zu 'ABC' - oder 'ABC' nach 'XYZ' einfügen - oder 'XYZ' entfernen"): the
 * integrate and final passes no longer re-emit the whole report — they emit
 * OPERATIONS against the standing text, parsed and applied deterministically
 * here. Untouched passages survive MECHANICALLY (no paraphrase drift, no
 * compression, no token-ceiling cut of unrelated sections), and the model's
 * output shrinks to just the changed prose — the report's growing length no
 * longer presses against numPredict.
 *
 * <p>Format — language-independent sentinel lines, each line-leading and alone
 * on its line (the format contract both prompt twins pin):
 * <pre>
 * &lt;&lt;&lt;REPLACE        &lt;&lt;&lt;INSERT-AFTER      &lt;&lt;&lt;DELETE
 * (alter Text)      (Anker-Text)         (Text)
 * &lt;&lt;&lt;WITH           &lt;&lt;&lt;WITH
 * (neuer Text)      (neuer Text)
 * &lt;&lt;&lt;END            &lt;&lt;&lt;END               &lt;&lt;&lt;END
 * </pre>
 *
 * <p>Anchors are matched verbatim first, then whitespace-tolerantly (runs of
 * whitespace treated as equivalent — a 4B's most common quoting drift). An op
 * whose anchor still isn't found is SKIPPED and reported, never fatal —
 * partial application beats losing the whole pass. Text outside op blocks
 * (model chatter) is ignored.
 */
final class EditScript {

    private static final Logger LOG = LoggerFactory.getLogger(EditScript.class);

    private static final String S_REPLACE = "<<<REPLACE";
    private static final String S_INSERT = "<<<INSERT-AFTER";
    private static final String S_DELETE = "<<<DELETE";
    private static final String S_WITH = "<<<WITH";
    private static final String S_END = "<<<END";
    /** The final pass's explicit "nothing to change" — distinct from a format whiff. */
    private static final String S_NOOP = "<<<NOOP";

    enum Type { REPLACE, INSERT_AFTER, DELETE }

    record Op(Type type, String anchor, String text) {
    }

    /** Outcome of {@link #apply}: the revised text plus what landed and what didn't. */
    record Result(String text, int applied, List<String> failures) {
    }

    private final List<Op> ops;
    private final boolean noop;

    private EditScript(List<Op> ops, boolean noop) {
        this.ops = ops;
        this.noop = noop;
    }

    static EditScript parse(String raw) {
        List<Op> ops = new ArrayList<>();
        boolean noop = false;
        if (raw == null || raw.isBlank()) return new EditScript(ops, false);
        String[] lines = raw.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i].strip();
            if (line.equals(S_NOOP)) {
                noop = true;
                i++;
                continue;
            }
            Type type = switch (line) {
                case S_REPLACE -> Type.REPLACE;
                case S_INSERT -> Type.INSERT_AFTER;
                case S_DELETE -> Type.DELETE;
                default -> null;
            };
            if (type == null) {
                i++;
                continue;
            }
            i++;
            StringBuilder anchor = new StringBuilder();
            StringBuilder text = new StringBuilder();
            StringBuilder cur = anchor;
            boolean closed = false;
            while (i < lines.length) {
                String inner = lines[i].strip();
                if (inner.equals(S_END)) {
                    closed = true;
                    i++;
                    break;
                }
                if (inner.equals(S_WITH) && type != Type.DELETE && cur == anchor) {
                    cur = text;
                    i++;
                    continue;
                }
                // A new op sentinel before END: treat the current block as closed
                // (a 4B occasionally forgets the terminator) and re-process the line.
                if (inner.equals(S_REPLACE) || inner.equals(S_INSERT) || inner.equals(S_DELETE)) {
                    closed = true;
                    break;
                }
                cur.append(lines[i]).append('\n');
                i++;
            }
            if (i >= lines.length) closed = true; // end of output terminates the last block
            String a = anchor.toString().strip();
            String t = text.toString().strip();
            if (!closed || a.isEmpty()) continue;
            if (type != Type.DELETE && t.isEmpty()) continue;
            ops.add(new Op(type, a, t));
        }
        return new EditScript(ops, noop && ops.isEmpty());
    }

    int size() {
        return ops.size();
    }

    boolean isEmpty() {
        return ops.isEmpty();
    }

    /** True when the pass explicitly declared "nothing to change" (and emitted no ops). */
    boolean isNoop() {
        return noop;
    }

    /**
     * Applies the ops in order against the evolving text. Every op resolves its
     * anchor independently; failures are collected, not thrown.
     */
    Result apply(String report) {
        String out = report;
        int applied = 0;
        List<String> failures = new ArrayList<>();
        for (Op op : ops) {
            int[] match = find(out, op.anchor());
            if (match == null) {
                failures.add(op.type() + ": anchor not found: \"" + preview(op.anchor()) + "\"");
                continue;
            }
            int start = match[0];
            int end = match[0] + match[1];
            switch (op.type()) {
                case REPLACE -> out = out.substring(0, start) + op.text() + out.substring(end);
                case INSERT_AFTER -> {
                    // After a full line/paragraph → own paragraph; mid-sentence → same line.
                    boolean atLineEnd = end >= out.length() || out.charAt(end) == '\n';
                    String sep = atLineEnd ? "\n\n" : " ";
                    out = out.substring(0, end) + sep + op.text() + out.substring(end);
                }
                case DELETE -> out = out.substring(0, start) + out.substring(end);
            }
            applied++;
        }
        out = out.replaceAll("\n{3,}", "\n\n").strip();
        return new Result(out, applied, failures);
    }

    /**
     * Finds the anchor: exact first, then whitespace-tolerant (every whitespace
     * run in the anchor matches any whitespace run in the text). Returns
     * {@code {start, length}} of the FIRST occurrence, or null.
     */
    private static int[] find(String haystack, String anchor) {
        int i = haystack.indexOf(anchor);
        if (i >= 0) return new int[]{i, anchor.length()};
        String[] tokens = anchor.split("\\s+");
        if (tokens.length == 0 || (tokens.length == 1 && tokens[0].isEmpty())) return null;
        StringBuilder rx = new StringBuilder();
        for (int t = 0; t < tokens.length; t++) {
            if (t > 0) rx.append("\\s+");
            rx.append(Pattern.quote(tokens[t]));
        }
        Matcher m = Pattern.compile(rx.toString()).matcher(haystack);
        if (m.find()) return new int[]{m.start(), m.end() - m.start()};
        return null;
    }

    private static String preview(String s) {
        String flat = s.replace('\n', ' ');
        return flat.length() <= 60 ? flat : flat.substring(0, 57) + "…";
    }

    /** One warn line per skipped op — visible in the DD's live log. */
    void logFailures(String subject, String passName, Result result) {
        for (String failure : result.failures()) {
            LOG.warn("[DEEPDIVE] '{}' {} op skipped — {}", subject, passName, failure);
        }
    }
}
