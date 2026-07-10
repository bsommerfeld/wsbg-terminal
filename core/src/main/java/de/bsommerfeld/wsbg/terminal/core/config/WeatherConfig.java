package de.bsommerfeld.wsbg.terminal.core.config;

import de.bsommerfeld.jshepherd.annotation.Comment;
import de.bsommerfeld.jshepherd.annotation.Key;

/**
 * Daily "Wetterbericht" parameters. Values are persisted in config.toml
 * and loaded at startup — setters only exist for the config framework and
 * the settings bridge.
 */
public class WeatherConfig {

    @Key("report-time")
    @Comment("Local time of day (HH:mm, 24h) at which the daily Wetterbericht "
            + "is written (default: 20:00). Changeable live from the widget; "
            + "the scheduler re-arms without a restart. If the app starts after "
            + "this time and no report exists for the day yet, the report is "
            + "written shortly after startup instead.")
    private String reportTime = "20:00";

    public String getReportTime() {
        return reportTime;
    }

    public void setReportTime(String reportTime) {
        this.reportTime = reportTime;
    }
}
