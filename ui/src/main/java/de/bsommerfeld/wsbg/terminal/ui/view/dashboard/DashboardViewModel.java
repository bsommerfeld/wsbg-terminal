package de.bsommerfeld.wsbg.terminal.ui.view.dashboard;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.ChatService;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;

import jakarta.inject.Inject;
import javafx.application.Platform;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class DashboardViewModel {

    private static final Logger LOG = LoggerFactory.getLogger(DashboardViewModel.class);

    private final ChatService chatService;
    private final GlobalConfig config;

    // Properties bound to UI

    private final ObservableList<LogMessage> logs = FXCollections.observableArrayList();

    @Inject
    public DashboardViewModel(ChatService chatService, GlobalConfig config) {
        this.chatService = chatService;
        this.config = config;

        initialize();
    }

    private void initialize() {
        LOG.info("Initializing Dashboard ViewModel...");

        // System Ready
        java.util.ResourceBundle bundle = java.util.ResourceBundle.getBundle("i18n.messages");
        appendToConsole(bundle.getString("system.terminal_ready"));
    }

    public void onAskAgent(String query) {
        appendToConsole("User: " + query, LogType.INFO);
        // Async call to agent
        chatService.sendUserMessage(query);
    }

    public void appendToConsole(String message) {
        appendToConsole(message, LogType.DEFAULT);
    }

    public void appendToConsole(String message, LogType type) {
        Platform.runLater(() -> {
            logs.add(new LogMessage(message + "\n", type));
            // Limit logs
            if (logs.size() > 1000) {
                logs.remove(0);
            }
        });
    }

    public ObservableList<LogMessage> getLogs() {
        return logs;
    }
}
