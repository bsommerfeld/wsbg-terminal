package de.bsommerfeld.wsbg.terminal.agent.event;

/**
 * Posted to update the status indicator in the UI.
 */
public record AgentStatusEvent(String status) {
}
