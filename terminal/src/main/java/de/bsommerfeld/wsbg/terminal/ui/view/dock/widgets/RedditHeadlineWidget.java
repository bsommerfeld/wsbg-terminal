package de.bsommerfeld.wsbg.terminal.ui.view.dock.widgets;

import com.google.common.eventbus.Subscribe;
import de.bsommerfeld.wsbg.terminal.agent.AgentBrain;
import de.bsommerfeld.wsbg.terminal.agent.event.AgentStreamEndEvent;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
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
import javafx.scene.control.ScrollPane;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RedditHeadlineWidget extends DockWidget {

    public static final String IDENTIFIER = "reddit-headline";

    private static final Logger LOG = LoggerFactory.getLogger(RedditHeadlineWidget.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // Reddit alien SVG path (simplified alien head)
    private static final String REDDIT_SVG = "M12 0A12 12 0 0 0 0 12a12 12 0 0 0 12 12 12 12 0 0 0 12-12A12 12 0 0 0 12 0zm5.01 4.744c.688 0 1.25.561 1.25 1.249a1.25 1.25 0 0 1-2.498.056l-2.597-.547-.8 3.747c1.824.07 3.48.632 4.674 1.488.308-.309.73-.491 1.207-.491.968 0 1.754.786 1.754 1.754 0 .716-.435 1.333-1.01 1.614a3.111 3.111 0 0 1 .042.52c0 2.694-3.13 4.87-7.004 4.87-3.874 0-7.004-2.176-7.004-4.87 0-.183.015-.366.043-.534A1.748 1.748 0 0 1 4.028 12c0-.968.786-1.754 1.754-1.754.463 0 .898.196 1.207.49 1.207-.883 2.878-1.43 4.744-1.487l.885-4.182a.342.342 0 0 1 .14-.197.35.35 0 0 1 .238-.042l2.906.617a1.214 1.214 0 0 1 1.108-.701zM9.25 12C8.561 12 8 12.562 8 13.25c0 .687.561 1.248 1.25 1.248.687 0 1.248-.561 1.248-1.249 0-.688-.561-1.249-1.249-1.249zm5.5 0c-.687 0-1.248.561-1.248 1.25 0 .687.561 1.248 1.249 1.248.688 0 1.249-.561 1.249-1.249 0-.687-.562-1.249-1.25-1.249zm-5.466 3.99a.327.327 0 0 0-.231.094.33.33 0 0 0 0 .463c.842.842 2.484.913 2.961.913.477 0 2.105-.056 2.961-.913a.361.361 0 0 0 .029-.463.33.33 0 0 0-.464 0c-.547.533-1.684.73-2.512.73-.828 0-1.979-.196-2.512-.73a.326.326 0 0 0-.232-.095z";

    /**
     * Ordered mapping of analysis keys to their display metadata.
     * Key → [label text, CSS style class].
     * Each line the AI produces in "KEY: value" format gets rendered as a
     * separate visual row — no freeform text blocks for the user to wade through.
     */
    private static final Map<String, String[]> ANALYSIS_FIELDS = new LinkedHashMap<>();

    static {
        ANALYSIS_FIELDS.put("ASSET",     new String[]{"ASSET",     "analysis-row-asset"});
        ANALYSIS_FIELDS.put("SENTIMENT", new String[]{"SENTIMENT", "analysis-row-sentiment"});
        ANALYSIS_FIELDS.put("SIGNAL",    new String[]{"SIGNAL",    "analysis-row-signal"});
        ANALYSIS_FIELDS.put("RISK",      new String[]{"RISK",      "analysis-row-risk"});
        ANALYSIS_FIELDS.put("NOTE",      new String[]{"NOTE",      "analysis-row-note"});
    }

    private final AgentRepository agentRepository;
    private final ApplicationEventBus eventBus;
    private final AgentBrain brain;
    private final I18nService i18n;
    private final ScheduledExecutorService pollScheduler;
    private final ExecutorService analysisExecutor;

    // Per-cluster analysis cache — avoids re-generating on every flip
    private final ConcurrentHashMap<String, String> analysisCache = new ConcurrentHashMap<>();

    private final ListView<HeadlineRecord> listView;
    private final Label statusLabel;
    private final Label backTitleLabel;
    private final Label analysisStatusLabel;

    // Back-side row container: populated by renderAnalysis()
    private final VBox analysisRows;

    public RedditHeadlineWidget(AgentRepository agentRepository, ApplicationEventBus eventBus,
            AgentBrain brain, I18nService i18n) {
        this.agentRepository = agentRepository;
        this.eventBus = eventBus;
        this.brain = brain;
        this.i18n = i18n;
        this.pollScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "reddit-headline-poll");
            t.setDaemon(true);
            return t;
        });
        this.analysisExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "reddit-analysis");
            t.setDaemon(true);
            return t;
        });

        this.listView = new ListView<>();
        this.statusLabel = new Label(i18n.get("widget.reddit.updating"));

        this.analysisStatusLabel = new Label();
        this.analysisStatusLabel.getStyleClass().add("fj-status");

        this.backTitleLabel = new Label(i18n.get("widget.reddit.details"));

        this.analysisRows = new VBox(6);
        this.analysisRows.setPadding(new Insets(10, 14, 10, 14));

        configureListView();
        buildWidget();
        startPolling();
        eventBus.register(this);
    }

    private void configureListView() {
        listView.getStyleClass().add("fj-list");
        listView.setCellFactory(l -> new HeadlineCell(i18n));
        listView.setPlaceholder(new Label(i18n.get("widget.reddit.empty")));

        listView.setOnMouseClicked(e -> {
            HeadlineRecord selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                showAnalysis(selected);
            }
        });
    }

    private void showAnalysis(HeadlineRecord record) {
        backTitleLabel.setText(record.headline());

        String cached = analysisCache.get(record.clusterId());
        if (cached != null) {
            renderAnalysis(cached);
            analysisStatusLabel.setText("");
            flip();
            return;
        }

        // Flip immediately with loading state, populate async
        renderAnalysis(null);
        analysisStatusLabel.setText(i18n.get("widget.reddit.analyzing"));
        flip();

        CompletableFuture.supplyAsync(() -> generateAnalysis(record), analysisExecutor)
                .thenAccept(analysis -> Platform.runLater(() -> {
                    analysisCache.put(record.clusterId(), analysis);
                    if (backTitleLabel.getText().equals(record.headline())) {
                        renderAnalysis(analysis);
                        analysisStatusLabel.setText("");
                    }
                }));
    }

    /**
     * Parses the structured AI response and populates {@code analysisRows}.
     * Each "KEY: value" line becomes a separate styled label row.
     * Unrecognized lines are ignored — keeps the back side clean even when
     * the model adds extra prose.
     */
    private void renderAnalysis(String raw) {
        analysisRows.getChildren().clear();
        if (raw == null || raw.isBlank())
            return;

        for (String line : raw.lines().toList()) {
            String trimmed = line.trim();
            for (Map.Entry<String, String[]> entry : ANALYSIS_FIELDS.entrySet()) {
                String key = entry.getKey() + ":";
                if (trimmed.startsWith(key)) {
                    String value = trimmed.substring(key.length()).trim();
                    if (value.isBlank())
                        continue;
                    String[] meta = entry.getValue();
                    HBox row = buildAnalysisRow(meta[0], value, meta[1]);
                    analysisRows.getChildren().add(row);
                    break;
                }
            }
        }
    }

    /**
     * Builds one analysis row: a fixed-width key badge + a value label.
     * The CSS class controls the badge color per field type.
     */
    private HBox buildAnalysisRow(String key, String value, String cssClass) {
        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().addAll("analysis-key", cssClass);
        keyLabel.setMinWidth(80);

        Label valLabel = new Label(value);
        valLabel.getStyleClass().add("analysis-value");
        valLabel.setWrapText(true);
        HBox.setHgrow(valLabel, Priority.ALWAYS);
        valLabel.setMaxWidth(Double.MAX_VALUE);

        HBox row = new HBox(10, keyLabel, valLabel);
        row.setAlignment(Pos.TOP_LEFT);
        row.getStyleClass().add("analysis-row");
        return row;
    }

    /**
     * Generates a tightly-scoped briefing using a fixed output schema.
     * The schema forces the AI to produce exactly 4-5 single-line fields
     * rather than freeform prose — making the result immediately scannable.
     */
    private String generateAnalysis(HeadlineRecord record) {
        try {
            String context = record.context();
            if (context == null || context.isBlank()) {
                return i18n.get("widget.reddit.no_context");
            }
            String lang = brain.getUserLanguage().displayName();
            String prompt = "You are a financial editor. Produce a 4-5 line structured briefing based on the Reddit cluster context below.\n"
                    + "Respond STRICTLY in this exact format (no other text, no markdown):\n"
                    + "ASSET: [ticker/instrument, max 3, comma-separated]\n"
                    + "SENTIMENT: [exactly one word: Bullish / Bearish / Mixed / Neutral]\n"
                    + "SIGNAL: [max 12 words — what is the community signaling?]\n"
                    + "RISK: [max 12 words — key risk factor]\n"
                    + "NOTE: [max 12 words — notable nuance or omit this line entirely]\n\n"
                    + "Write in " + lang + ". Tickers stay in their original form.\n\n"
                    + "CONTEXT:\n" + context;
            String result = brain.ask("reddit-widget-" + record.clusterId(), prompt);
            return result != null ? result.trim() : i18n.get("widget.reddit.analysis_failed");
        } catch (Exception e) {
            LOG.warn("Analysis generation failed for cluster {}", record.clusterId(), e);
            return i18n.get("widget.reddit.analysis_failed");
        }
    }

    private void startPolling() {
        pollScheduler.schedule(this::poll, 1, TimeUnit.SECONDS);
        pollScheduler.scheduleAtFixedRate(this::poll, 60, 60, TimeUnit.SECONDS);
    }

    private void poll() {
        try {
            List<HeadlineRecord> newItems = agentRepository.getRecentHeadlines();
            Platform.runLater(() -> {
                listView.getItems().setAll(newItems);
                statusLabel.setText(i18n.get("widget.reddit.status", newItems.size(),
                        LocalDateTime.now().format(TIME_FORMAT)));
            });
        } catch (Exception e) {
            Platform.runLater(() -> statusLabel.setText(i18n.get("widget.reddit.error")));
        }
    }

    /**
     * Reacts to new passive-monitor headlines in real-time.
     * {@code saveHeadline()} is always called before the event is posted, so
     * the full record (including context) already lives in the
     * {@link AgentRepository} cache. A direct poll() reads it instantly —
     * no stub with empty context is needed.
     */
    @Subscribe
    public void onAgentStreamEnd(AgentStreamEndEvent event) {
        String msg = event.fullMessage();
        if (msg == null || !msg.startsWith("||PASSIVE||"))
            return;
        // Refresh from cache immediately so the new record appears with full context
        pollScheduler.submit(this::poll);
    }

    public void shutdown() {
        eventBus.unregister(this);
        pollScheduler.shutdownNow();
        analysisExecutor.shutdownNow();
    }

    // -- DockWidget front layout --

    @Override
    protected Node topPane() {
        SVGPath logo = new SVGPath();
        logo.setContent(REDDIT_SVG);
        logo.setFill(Color.web("#ff4500"));
        logo.setScaleX(0.75);
        logo.setScaleY(0.75);

        Label title = new Label(i18n.get("widget.reddit.title"));
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
    protected Node leftPane() { return null; }

    @Override
    protected Node rightPane() { return null; }

    @Override
    protected Node bottomPane() { return null; }

    // -- DockWidget back layout --

    @Override
    protected Node backTopPane() {
        backTitleLabel.getStyleClass().add("fj-title");
        backTitleLabel.setWrapText(true);
        backTitleLabel.setMaxWidth(Double.MAX_VALUE);

        Button backButton = new Button(i18n.get("widget.reddit.back"));
        backButton.setOnAction(e -> flip());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox titleRow = new HBox(backTitleLabel, spacer, backButton);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(backTitleLabel, Priority.ALWAYS);

        VBox header = new VBox(4, titleRow, analysisStatusLabel);
        header.setPadding(new Insets(8, 12, 6, 12));
        return header;
    }

    @Override
    protected Node backCenterPane() {
        ScrollPane scroll = new ScrollPane(analysisRows);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return scroll;
    }

    @Override
    public String getIdentifier() {
        return IDENTIFIER;
    }

    // -- Cell renderer --

    private static class HeadlineCell extends ListCell<HeadlineRecord> {

        private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

        private final VBox rootNode = new VBox(4);
        private final Circle outerCircle = new Circle(5);
        private final Circle innerCircle = new Circle(2.5);
        private final Label headlineLabel = new Label();
        private final Label timeLabel = new Label();
        private final I18nService i18n;

        HeadlineCell(I18nService i18n) {
            this.i18n = i18n;
            setPadding(Insets.EMPTY);

            headlineLabel.getStyleClass().add("fj-cell-headline");
            headlineLabel.setWrapText(true);
            headlineLabel.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(headlineLabel, Priority.ALWAYS);

            timeLabel.getStyleClass().add("fj-cell-time");

            outerCircle.setFill(Color.TRANSPARENT);
            outerCircle.setStroke(Color.web("#ff4500"));
            outerCircle.setStrokeWidth(1.5);
            innerCircle.setFill(Color.web("#ff4500"));

            StackPane indicator = new StackPane(outerCircle, innerCircle);
            indicator.setMinWidth(20);
            indicator.setAlignment(Pos.TOP_CENTER);
            indicator.setPadding(new Insets(3, 0, 0, 0));

            HBox topBox = new HBox(8, indicator, headlineLabel);
            topBox.setAlignment(Pos.TOP_LEFT);

            rootNode.getChildren().addAll(topBox, timeLabel);
            rootNode.setPadding(new Insets(10, 14, 10, 14));

            // Bind width to prevent horizontal scrollbar — subtract scrollbar gutter
            prefWidthProperty().bind(listViewProperty()
                    .flatMap(Region::widthProperty)
                    .map(w -> w.doubleValue() - 30));
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
                    ? LocalDateTime.ofInstant(Instant.ofEpochSecond(item.createdAt()), ZoneId.systemDefault())
                            .format(TIME_FORMAT)
                    : "--:--";

            headlineLabel.setText(item.headline());
            timeLabel.setText(time);
            setGraphic(rootNode);
        }
    }
}
