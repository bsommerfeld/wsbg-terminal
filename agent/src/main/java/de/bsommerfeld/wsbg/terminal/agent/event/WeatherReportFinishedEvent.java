package de.bsommerfeld.wsbg.terminal.agent.event;

/**
 * The daily Wetterbericht generation ended for {@code date} (ISO local date).
 * On success the report is already appended to the archive when this fires.
 */
public record WeatherReportFinishedEvent(String date, boolean success) {
}
