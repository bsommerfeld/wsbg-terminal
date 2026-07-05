package de.bsommerfeld.wsbg.terminal.agent;

/**
 * Stateless string helpers for building evidence snippets (extracted from
 * {@link SubjectAttributor}). Kept tiny and dependency-free so both the evidence
 * collector and the {@link EventConsolidator} can share one copy.
 */
final class EvidenceText {

    private EvidenceText() {
    }

    static String snippet(String s) {
        if (s == null) return "";
        String one = s.replace('\n', ' ').strip();
        return one.length() > 140 ? one.substring(0, 140) + "…" : one;
    }

    /** A wider snippet for context lines — the thesis a pick answers needs room to read. */
    static String contextSnippet(String s) {
        if (s == null) return "";
        String one = s.replace('\n', ' ').strip();
        return one.length() > 240 ? one.substring(0, 240) + "…" : one;
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }
}
