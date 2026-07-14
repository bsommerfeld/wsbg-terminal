package de.bsommerfeld.wsbg.terminal.agent.event;

import java.util.List;

/**
 * One desk-journal HUNK for the KI-DD's live review pane — the EFFECT of one
 * pipeline step as a GitHub-style diff container, never model narration.
 * Kinds: {@code add} = sentence entered, {@code del} = sentence left,
 * {@code ctx} = unchanged context around an edit, {@code gap} = elided
 * unchanged run ("⋯"), {@code note} = an annotation on text that changed
 * nothing (an objection, a rejected source, a gate verdict). {@code oldLine}
 * / {@code newLine} are 1-based sentence numbers in the before/after state
 * (0 = not applicable) — the dual gutter of the diff view.
 */
public record DeepDiveJournalEvent(String subject, List<Line> lines) {

    public record Line(String kind, String text, int oldLine, int newLine) {

        public Line(String kind, String text) {
            this(kind, text, 0, 0);
        }
    }
}
