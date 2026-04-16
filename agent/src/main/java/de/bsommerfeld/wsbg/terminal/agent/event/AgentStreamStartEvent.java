package de.bsommerfeld.wsbg.terminal.agent.event;

/**
 * Posted when an AI response begins. Optional source label and CSS class
 * for UI styling.
 */
public record AgentStreamStartEvent(String source, String cssClass) {

    public AgentStreamStartEvent() {
        this(null, null);
    }
}
