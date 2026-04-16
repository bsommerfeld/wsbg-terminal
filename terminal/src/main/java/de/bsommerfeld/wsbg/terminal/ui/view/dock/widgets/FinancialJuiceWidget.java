package de.bsommerfeld.wsbg.terminal.ui.view.dock.widgets;

import de.bsommerfeld.wsbg.terminal.core.domain.FjNewsItem;
import de.bsommerfeld.wsbg.terminal.core.i18n.I18nService;
import de.bsommerfeld.wsbg.terminal.fj.FjScraper;
import de.bsommerfeld.wsbg.terminal.ui.view.dock.DockWidget;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ListCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Displays live FinancialJuice RSS headlines with 60-second polling.
 * Items are shown newest-first with timestamp and headline text.
 *
 * <p>
 * 60 seconds is the minimum safe interval — FJ rate-limits
 * aggressively (HTTP 429 at anything below ~30s). Since the feed
 * delivers all items per request, nothing is missed.
 */
public class FinancialJuiceWidget extends DockWidget {

    public static final String IDENTIFIER = "financial-juice";

    // Simple bar-chart SVG icon used as a neutral market-data logo
    private static final String CHART_SVG = "M3 3v18h18V3H3zm16 16H5V5h14v14zM7 14h2v3H7v-3zm4-4h2v7h-2v-7zm4-3h2v10h-2V7z";
    private static final Logger LOG = LoggerFactory.getLogger(FinancialJuiceWidget.class);
    // FJ returns 429 at intervals below ~30s, 60s is safe
    private static final long POLL_INTERVAL_SECONDS = 60;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final FjScraper scraper;
    private final ScheduledExecutorService scheduler;
    private final ListView<FjNewsItem> listView;
    private final Label statusLabel;
    private final I18nService i18n;

    public FinancialJuiceWidget(I18nService i18n) {
        this.i18n = i18n;
        this.scraper = new FjScraper();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "fj-poll");
            t.setDaemon(true);
            return t;
        });
        this.listView = new ListView<>();
        this.statusLabel = new Label(i18n.get("widget.fj.updating"));

        configureListView();
        buildWidget();
        startPolling();
    }

    private void configureListView() {
        listView.getStyleClass().add("fj-list");
        listView.setCellFactory(l -> new FjNewsCell(i18n));
        listView.setPlaceholder(new Label(i18n.get("widget.fj.empty")));
    }

    private void startPolling() {
        // Initial fetch with small delay so the UI renders first
        scheduler.schedule(this::poll, 1, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::poll,
                POLL_INTERVAL_SECONDS + 1, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void poll() {
        try {
            List<FjNewsItem> newItems = scraper.fetch();
            Platform.runLater(() -> {
                // Prepend new items at the top (newest first)
                for (int i = newItems.size() - 1; i >= 0; i--) {
                    listView.getItems().addFirst(newItems.get(i));
                }
                // Cap at 200 items to prevent unbounded memory growth
                if (listView.getItems().size() > 200) {
                    listView.getItems().remove(200, listView.getItems().size());
                }
                statusLabel.setText(i18n.get("widget.fj.status",
                        scraper.seenCount(),
                        LocalDateTime.now().format(TIME_FORMAT)));
            });
        } catch (Exception e) {
            LOG.error("FJ poll failed", e);
            Platform.runLater(() -> statusLabel.setText(i18n.get("widget.fj.error")));
        }
    }

    /**
     * Stops the background scheduler. Called implicitly when the
     * widget is removed from the dock.
     */
    public void shutdown() {
        scheduler.shutdownNow();
    }

    // ── DockWidget layout ────────────────────────────────────────────

    @Override
    protected Node topPane() {
        SVGPath logo = new SVGPath();
        logo.setContent(CHART_SVG);
        logo.setFill(Color.web("#ff9f00"));
        logo.setScaleX(0.75);
        logo.setScaleY(0.75);

        Label title = new Label(i18n.get("widget.fj.title"));
        title.getStyleClass().add("fj-title");

        statusLabel.getStyleClass().add("fj-status");

        HBox header = new HBox(6, logo, title, statusLabel);
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
    protected Node leftPane() {
        return null;
    }

    @Override
    protected Node rightPane() {
        return null;
    }

    @Override
    protected Node bottomPane() {
        return null;
    }

    @Override
    public String getIdentifier() {
        return IDENTIFIER;
    }

    // ── Cell renderer ────────────────────────────────────────────────

    private static class FjNewsCell extends ListCell<FjNewsItem> {

        private final VBox rootNode = new VBox(8);
        private final Circle outerCircle = new Circle(6);
        private final Circle innerCircle = new Circle(3);
        private final Label headlineLabel = new Label();
        private final Label subtextLabel = new Label();
        private final ImageView imageView = new ImageView();
        private final Label timeLabel = new Label();
        private final FlowPane bottomBox = new FlowPane(6, 6);
        private final I18nService i18n;
        
        private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("HH:mm MMM dd");

        FjNewsCell(I18nService i18n) {
            this.i18n = i18n;
            setPadding(Insets.EMPTY);
            
            headlineLabel.getStyleClass().add("fj-cell-headline");
            headlineLabel.setWrapText(true);
            headlineLabel.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(headlineLabel, Priority.ALWAYS);
            
            subtextLabel.getStyleClass().add("fj-cell-subtext");
            subtextLabel.setWrapText(true);
            
            timeLabel.getStyleClass().add("fj-cell-time");
            
            imageView.setPreserveRatio(true);
            // 32 equals 16px left + 16px right padding from rootNode
            imageView.fitWidthProperty().bind(rootNode.widthProperty().subtract(32));
            
            outerCircle.setFill(Color.TRANSPARENT);
            outerCircle.setStrokeWidth(2.0);
            
            StackPane indicator = new StackPane(outerCircle, innerCircle);
            indicator.setMinWidth(24);
            indicator.setAlignment(Pos.TOP_CENTER);
            indicator.setPadding(new Insets(3, 0, 0, 0));
            
            HBox topBox = new HBox(8, indicator, headlineLabel);
            topBox.setAlignment(Pos.TOP_LEFT);
            
            bottomBox.setAlignment(Pos.CENTER_LEFT);
            
            rootNode.getChildren().addAll(topBox, subtextLabel, imageView, bottomBox);
            rootNode.setPadding(new Insets(12, 16, 12, 16));
            
            // To ensure the cell itself never exceeds the list view width (subtracting 30px to leave room for vertical scrollbar)
            prefWidthProperty().bind(listViewProperty().flatMap(Region::widthProperty).map(w -> w.doubleValue() - 30));
        }

        @Override
        protected void updateItem(FjNewsItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                getStyleClass().remove("fj-cell-red");
                return;
            }

            if (item.isRed()) {
                if (!getStyleClass().contains("fj-cell-red")) {
                    getStyleClass().add("fj-cell-red");
                }
                outerCircle.setStroke(Color.web("#ff4444"));
                innerCircle.setFill(Color.web("#ff4444"));
            } else {
                getStyleClass().remove("fj-cell-red");
                outerCircle.setStroke(Color.web("#44aaff"));
                innerCircle.setFill(Color.web("#44aaff"));
            }

            String time = item.publishedUtc() > 0
                    ? LocalDateTime.ofInstant(Instant.ofEpochSecond(item.publishedUtc()), ZoneId.systemDefault()).format(DAY_FORMAT)
                    : "--:--";
            timeLabel.setText(time);

            headlineLabel.setText(item.title());
            
            if (item.description() != null && !item.description().isBlank()) {
                subtextLabel.setText(item.description());
                subtextLabel.setManaged(true);
                subtextLabel.setVisible(true);
            } else {
                subtextLabel.setManaged(false);
                subtextLabel.setVisible(false);
            }
            
            if (item.imageUrl() != null && !item.imageUrl().isBlank()) {
                try {
                    imageView.setImage(new Image(item.imageUrl(), true));
                    imageView.setManaged(true);
                    imageView.setVisible(true);
                } catch (Exception e) {
                    imageView.setManaged(false);
                    imageView.setVisible(false);
                }
            } else {
                imageView.setImage(null);
                imageView.setManaged(false);
                imageView.setVisible(false);
            }
            
            bottomBox.getChildren().clear();
            bottomBox.getChildren().add(timeLabel);
            if (item.tags() != null) {
                for (String tag : item.tags()) {
                    Label tagLabel = new Label(tag);
                    tagLabel.getStyleClass().add("fj-cell-tag");
                    bottomBox.getChildren().add(tagLabel);
                }
            }

            setGraphic(rootNode);
        }
    }
}
