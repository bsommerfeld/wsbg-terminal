package de.bsommerfeld.wsbg.terminal.ui.event;

/**
 * UI-only events that never leave the terminal module.
 * Separated from core events to keep the agent module
 * free of UI dependencies.
 */
public class UiEvents {

    /** Clears all rendered log entries in the terminal WebView. */
    public record ClearTerminalEvent() {
    }

    /** Propagates a search query to all views that support text highlighting. */
    public record SearchEvent(String query) {
    }

    /** Jumps to the next occurrence of the active search term. */
    public record SearchNextEvent() {
    }

    /** Switches the main content area between terminal and graph view. */
    public record ToggleGraphViewEvent() {
    }

    /**
     * Pulses the graph toggle button in the title bar via fade animation.
     * Posted when the graph sidebar triggers an AI analysis,
     * cleared when the analysis stream completes.
     */
    public record TerminalBlinkEvent(boolean active) {
    }
}
