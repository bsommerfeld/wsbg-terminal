package de.bsommerfeld.wsbg.terminal.agent.event;

/**
 * Posted when an AI response completes with the full message.
 */
public record AgentStreamEndEvent(String fullMessage) {
}
