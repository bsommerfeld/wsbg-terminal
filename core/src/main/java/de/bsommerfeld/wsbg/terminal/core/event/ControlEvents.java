package de.bsommerfeld.wsbg.terminal.core.event;

/**
 * Cross-module events bridging the agent layer and the UI layer.
 * Only events that both modules need to produce or consume belong here.
 */
public class ControlEvents {

    public record TriggerAgentAnalysisEvent(String prompt) {
    }

    public record LogEvent(String message, String type) {
        public LogEvent(String message) {
            this(message, "INFO");
        }
    }

    /**
     * Fired when the user toggles Power Mode in settings. AgentBrain reinitializes
     * its models.
     */
    public record PowerModeChangedEvent() {
    }

    /**
     * Fired when the UI language is changed. Components update their localized
     * strings.
     */
    public record LanguageChangedEvent() {
    }
}
