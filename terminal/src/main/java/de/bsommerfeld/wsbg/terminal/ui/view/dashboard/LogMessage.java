package de.bsommerfeld.wsbg.terminal.ui.view.dashboard;

/** A single terminal log entry with its display classification. */
public record LogMessage(String message, LogType type) {
}
