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
}
