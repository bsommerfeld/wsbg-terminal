package de.bsommerfeld.wsbg.terminal.agent.event;

/**
 * Posted whenever the AI watchlist changed — an entry was added/removed, a
 * mapping to a live subject unit was made, or a report revision landed. Pure
 * signal: the UI bridge re-reads the current state from the service.
 */
public record WatchlistChangedEvent() {
}
