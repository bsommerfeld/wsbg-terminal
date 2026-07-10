package de.bsommerfeld.wsbg.terminal.agent.event;

/** The daily Wetterbericht generation began for {@code date} (ISO local date). */
public record WeatherReportStartedEvent(String date) {
}
