package de.bsommerfeld.wsbg.terminal.ui.view.dashboard;

public class LogMessage {
    private final String message;
    private final LogType type;

    public LogMessage(String message, LogType type) {
        this.message = message;
        this.type = type;
    }

    public String getMessage() {
        return message;
    }

    public LogType getType() {
        return type;
    }
}
