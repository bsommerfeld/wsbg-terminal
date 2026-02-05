package de.bsommerfeld.wsbg.terminal.core.event;

/**
 * Events for controlling the Application UI/System from the Agent.
 */
public class ControlEvents {

    public static class OpenTabEvent {
        public final String tabName;

        public OpenTabEvent(String tabName) {
            this.tabName = tabName;
        }
    }

    public static class TriggerAgentAnalysisEvent {
        public final String prompt;

        public TriggerAgentAnalysisEvent(String prompt) {
            this.prompt = prompt;
        }
    }

    public static class LogEvent {
        public final String message;
        public final String type; // INFO, ERROR, WARN

        public LogEvent(String message, String type) {
            this.message = message;
            this.type = type;
        }

        public LogEvent(String message) {
            this(message, "INFO");
        }
    }

    public static class ClearTerminalEvent {
        public ClearTerminalEvent() {
        }
    }

    public static class SearchEvent {
        public final String query;

        public SearchEvent(String query) {
            this.query = query;
        }
    }

    public static class SearchNextEvent {
        public SearchNextEvent() {
        }
    }

    public static class ToggleRedditPanelEvent {
        public final boolean visible;

        public ToggleRedditPanelEvent(boolean visible) {
            this.visible = visible;
        }
    }

    public static class RedditSearchResultsEvent {
        public final boolean hasResults;

        public RedditSearchResultsEvent(boolean hasResults) {
            this.hasResults = hasResults;
        }
    }
}
