package de.bsommerfeld.wsbg.terminal.ui.view.dashboard;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.ChatService;
import de.bsommerfeld.wsbg.terminal.core.config.ApplicationMode;

import jakarta.inject.Inject;
import javafx.application.Platform;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Observable model backing the terminal dashboard. Manages the log buffer
 * and dispatches user queries to the chat service.
 */
@Singleton
public class DashboardViewModel {

    private static final Logger LOG = LoggerFactory.getLogger(DashboardViewModel.class);

    private final ChatService chatService;
    private final ObservableList<LogMessage> logs = FXCollections.observableArrayList();

    @Inject
    public DashboardViewModel(ChatService chatService) {
        this.chatService = chatService;

        initialize();
    }

    /**
     * Displays the ASCII art boot banner on a background thread
     * with a 600ms startup delay. The banner characters encode
     * color markers that the controller resolves to styled HTML spans.
     */
    private void initialize() {
        LOG.info("Initializing Dashboard ViewModel...");

        // Simulate Boot Sequence
        new Thread(() -> {
            try {
                // Initial Silence
                Thread.sleep(600);

                // WSB Part (Common Left Side)
                String[] wsbLines = {
                        "{{B}}██╗    ██╗███████╗██████╗ ",
                        "{{B}}██║    ██║██╔════╝██╔══██╗",
                        "{{B}}██║ █╗ ██║███████╗██████╔╝",
                        "{{B}}██║███╗██║╚════██║██╔══██╗",
                        "{{B}}╚███╔███╔╝███████║██████╔╝",
                        "{{B}} ╚══╝╚══╝ ╚══════╝╚═════╝ "
                };

                String[] suffixLines;

                if (ApplicationMode.get().isTest()) {
                    // Test Mode: Blue "T" -> "WSBT"
                    suffixLines = new String[] {
                            "{{BLUE}}████████╗{{X}}",
                            "{{BLUE}}╚══██╔══╝{{X}}",
                            "{{BLUE}}   ██║   {{X}}",
                            "{{BLUE}}   ██║   {{X}}",
                            "{{BLUE}}   ██║   {{X}}",
                            "{{BLUE}}   ╚═╝   {{X}}"
                    };
                } else {
                    // Production Mode: Germany "G" -> "WSBG"
                    suffixLines = new String[] {
                            "{{K}}██████╗{{X}}",
                            "{{K}}██╔════╝{{X}}",
                            "{{R}}██║  ███╗{{X}}",
                            "{{R}}██║   ██║{{X}}",
                            "{{Y}}╚██████╔╝{{X}}",
                            "{{Y}} ╚═════╝{{X}}"
                    };
                }

                // Combine Parts
                StringBuilder banner = new StringBuilder();
                for (int i = 0; i < wsbLines.length; i++) {
                    banner.append(wsbLines[i]).append(suffixLines[i]);
                    if (i < wsbLines.length - 1) {
                        banner.append("\n");
                    }
                }

                appendToConsole("||BANNER||" + banner.toString());
            } catch (InterruptedException e) {
                // Ignore
            }
        }).start();
    }

    /**
     * Appends the user query to the log buffer and forwards it to the AI chat
     * service.
     */
    public void onAskAgent(String query) {
        appendToConsole("User: " + query, LogType.INFO);
        // Async call to agent
        chatService.sendUserMessage(query);
    }

    /** Shorthand: appends a message with {@link LogType#DEFAULT}. */
    public void appendToConsole(String message) {
        appendToConsole(message, LogType.DEFAULT);
    }

    /**
     * Appends a typed log message. Runs on the FX thread and trims
     * the buffer to 1 000 entries to cap memory usage.
     */
    public void appendToConsole(String message, LogType type) {
        Platform.runLater(() -> {
            logs.add(new LogMessage(message + "\n", type));
            // Limit logs
            if (logs.size() > 1000) {
                logs.remove(0);
            }
        });
    }

    /** Observable log list. Changes fire ListChangeListeners on the FX thread. */
    public ObservableList<LogMessage> getLogs() {
        return logs;
    }
}
