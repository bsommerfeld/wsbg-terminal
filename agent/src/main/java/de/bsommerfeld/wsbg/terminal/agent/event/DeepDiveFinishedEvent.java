package de.bsommerfeld.wsbg.terminal.agent.event;

/**
 * A KI-DD generation ended. On success the report is already archived (its id
 * in {@code reportId}) when this fires; on failure {@code reportId} is null.
 */
public record DeepDiveFinishedEvent(String subject, boolean success, String reportId) {
}
