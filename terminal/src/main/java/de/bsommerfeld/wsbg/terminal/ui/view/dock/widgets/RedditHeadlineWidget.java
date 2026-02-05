package de.bsommerfeld.wsbg.terminal.ui.view.dock.widgets;

import de.bsommerfeld.wsbg.terminal.core.i18n.I18nService;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.db.DatabaseService.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.ui.view.dock.DockWidget;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RedditHeadlineWidget extends DockWidget {

    public static final String IDENTIFIER = "reddit-headline";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final AgentRepository agentRepository;
    private final I18nService i18n;
    private final ScheduledExecutorService scheduler;
    
    private final ListView<HeadlineRecord> listView;
    private final Label statusLabel;
    
    private final TextArea contextArea;
    private final Label backTitleLabel;

    public RedditHeadlineWidget(AgentRepository agentRepository, I18nService i18n) {
        this.agentRepository = agentRepository;
        this.i18n = i18n;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "reddit-headline-poll");
            t.setDaemon(true);
            return t;
        });
        
        this.listView = new ListView<>();
        this.statusLabel = new Label(i18n.get("widget.reddit.updating"));
        
        this.contextArea = new TextArea();
        this.contextArea.setEditable(false);
        this.contextArea.setWrapText(true);
        this.contextArea.getStyleClass().add("reddit-context-area");
        
        this.backTitleLabel = new Label(i18n.get("widget.reddit.details"));

        configureListView();
        buildWidget();
        startPolling();
    }

    private void configureListView() {
        listView.getStyleClass().add("fj-list"); 
        listView.setCellFactory(l -> new HeadlineCell());
        listView.setPlaceholder(new Label(i18n.get("widget.reddit.empty")));
        
        listView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 1 || e.getClickCount() == 2) {
                HeadlineRecord selected = listView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    showDetails(selected);
                }
            }
        });
    }
    
    private void showDetails(HeadlineRecord record) {
        backTitleLabel.setText(record.headline());
        contextArea.setText(record.context());
        flip();
    }

    private void startPolling() {
        scheduler.schedule(this::poll, 1, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::poll, 60, 60, TimeUnit.SECONDS);
    }

    private void poll() {
        try {
            List<HeadlineRecord> newItems = agentRepository.getRecentHeadlines();
            Platform.runLater(() -> {
                listView.getItems().setAll(newItems);
                statusLabel.setText(i18n.get("widget.reddit.status", newItems.size(), LocalDateTime.now().format(TIME_FORMAT)));
            });
        } catch (Exception e) {
            Platform.runLater(() -> statusLabel.setText(i18n.get("widget.reddit.error")));
        }
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    // -- DockWidget front layout --

    @Override
    protected Node topPane() {
        Label title = new Label(i18n.get("widget.reddit.title"));
        title.getStyleClass().add("fj-title"); 

        statusLabel.getStyleClass().add("fj-status");

        HBox header = new HBox(title, statusLabel);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(8, 12, 6, 12));
        HBox.setHgrow(statusLabel, Priority.ALWAYS);
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        statusLabel.setAlignment(Pos.CENTER_RIGHT);

        return header;
    }

    @Override
    protected Node centerPane() {
        return listView;
    }

    @Override
    protected Node leftPane() { return null; }

    @Override
    protected Node rightPane() { return null; }

    @Override
    protected Node bottomPane() { return null; }
    
    // -- DockWidget back layout --
    
    @Override
    protected Node backTopPane() {
        backTitleLabel.getStyleClass().add("fj-title");
        backTitleLabel.setMaxWidth(300); // basic clamp
        
        Button backButton = new Button(i18n.get("widget.reddit.back"));
        backButton.setOnAction(e -> flip());
        
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(backTitleLabel, spacer, backButton);
        header.setAlignment(Pos.CENTER_RIGHT);
        header.setPadding(new Insets(8, 12, 6, 12));
        return header;
    }
    
    @Override
    protected Node backCenterPane() {
        return contextArea;
    }

    @Override
    public String getIdentifier() {
        return IDENTIFIER;
    }

    // -- Cell renderer --

    private static class HeadlineCell extends ListCell<HeadlineRecord> {
        private final Label timeLabel = new Label();
        private final Label headlineLabel = new Label();
        private final HBox container = new HBox(8);

        HeadlineCell() {
            timeLabel.getStyleClass().add("fj-cell-time");
            timeLabel.setMinWidth(60);
            timeLabel.setAlignment(Pos.CENTER_RIGHT);

            headlineLabel.getStyleClass().add("fj-cell-headline");
            headlineLabel.setWrapText(true);
            headlineLabel.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(headlineLabel, Priority.ALWAYS);

            container.setAlignment(Pos.CENTER_LEFT);
            container.setPadding(new Insets(4, 8, 4, 8));
            container.getChildren().addAll(timeLabel, headlineLabel);
        }

        @Override
        protected void updateItem(HeadlineRecord item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                return;
            }

            String time = item.createdAt() > 0
                    ? LocalDateTime.ofInstant(
                            Instant.ofEpochSecond(item.createdAt()),
                            ZoneId.systemDefault())
                            .format(TIME_FORMAT)
                    : "--:--:--";

            timeLabel.setText(time);
            headlineLabel.setText(item.headline());

            setGraphic(container);
        }
    }
}
