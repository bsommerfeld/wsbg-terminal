package de.bsommerfeld.wsbg.terminal.ui.view.settings;

import com.google.inject.Inject;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import javafx.fxml.FXML;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import java.util.Arrays;
import java.util.stream.Collectors;

public class SettingsController {

    @FXML
    private TextField modelField;
    @FXML
    private TextArea subredditsField;

    private final GlobalConfig config;
    private final ApplicationEventBus eventBus;

    @Inject
    public SettingsController(GlobalConfig config, ApplicationEventBus eventBus) {
        this.config = config;
        this.eventBus = eventBus;
    }

    @FXML
    public void initialize() {
        loadValues();
    }

    private void loadValues() {
        modelField.setText(config.getAgent().getOllamaModel());

        subredditsField.setText(String.join(", ", config.getAgent().getSubreddits()));
    }

    @FXML
    public void onSave() {
        // 1. Update Runtime Config
        config.getAgent().setOllamaModel(modelField.getText());

        String subsText = subredditsField.getText();
        java.util.List<String> subList = Arrays.stream(subsText.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        config.getAgent().setSubreddits(subList);

        // 2. Persist to YAML
        try {
            config.save();
            eventBus.post(new de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent(
                    "Configuration Saved & Persisted!", "INFO"));
        } catch (Exception e) {
            eventBus.post(new de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent(
                    "Error Saving Config: " + e.getMessage(), "ERROR"));
        }
    }

    @FXML
    public void onReload() {
        loadValues();
    }
}
