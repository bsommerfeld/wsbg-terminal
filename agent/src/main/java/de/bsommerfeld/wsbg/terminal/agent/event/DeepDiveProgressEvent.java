package de.bsommerfeld.wsbg.terminal.agent.event;

/**
 * KI-DD generation progress for {@code subject}. {@code stage} is one of
 * {@code collect | draft | integrate | qa | final} — the UI maps each to a
 * localized label. {@code detail} narrates within a stage (the integrate
 * passes: "2/5 · Analysten & Insider"), may be null.
 */
public record DeepDiveProgressEvent(String subject, String stage, String detail) {

    public DeepDiveProgressEvent(String subject, String stage) {
        this(subject, stage, null);
    }
}
