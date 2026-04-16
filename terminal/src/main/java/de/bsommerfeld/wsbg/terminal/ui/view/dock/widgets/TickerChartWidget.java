package de.bsommerfeld.wsbg.terminal.ui.view.dock.widgets;

import com.google.common.eventbus.Subscribe;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.TickerSnapshotEvent;
import de.bsommerfeld.wsbg.terminal.core.i18n.I18nService;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.ui.view.dock.DockWidget;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.PieChart;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

import java.util.Map;

/**
 * Displays a Pie Chart showing the distribution of mentions for different
 * financial instruments (stocks, ETFs, etc.) based on AI extractions.
 * Bootstraps from the cache on startup to avoid appearing blank after restarts.
 */
public class TickerChartWidget extends DockWidget {

    public static final String IDENTIFIER = "ticker-chart";

    // Donut/pie-chart SVG icon
    private static final String PIE_SVG = "M11 2v20c-5.07-.5-9-4.79-9-10s3.93-9.5 9-10zm2.03 0v8.99H22c-.47-4.74-4.24-8.52-8.97-8.99zm0 11.01V22c4.74-.47 8.5-4.25 8.97-8.99h-8.97z";

    private final ApplicationEventBus eventBus;
    private final I18nService i18n;
    private final PieChart pieChart;
    private final Label titleLabel;
    private final Label emptyLabel;
    private final AgentRepository agentRepository;

    public TickerChartWidget(ApplicationEventBus eventBus, I18nService i18n, AgentRepository agentRepository) {
        this.eventBus = eventBus;
        this.i18n = i18n;
        this.agentRepository = agentRepository;
        this.pieChart = new PieChart();
        this.pieChart.setLegendVisible(false);
        this.pieChart.setLabelsVisible(true);
        this.pieChart.setAnimated(true);

        this.emptyLabel = new Label(i18n.get("widget.ticker_chart.empty"));
        this.emptyLabel.getStyleClass().add("fj-status");

        this.titleLabel = new Label(i18n.get("widget.ticker_chart.title"));
        this.titleLabel.getStyleClass().add("fj-title");

        buildWidget();
        eventBus.register(this);

        // Warm from cache — avoids blank widget after a restart when data already exists
        Map<String, Integer> cached = agentRepository.getTickerCountsLastHour();
        if (!cached.isEmpty()) {
            updateChart(cached);
        }
    }

    @Subscribe
    public void onTickerSnapshot(TickerSnapshotEvent event) {
        Platform.runLater(() -> updateChart(event.mentionsPerTicker()));
    }

    private void updateChart(Map<String, Integer> mentions) {
        ObservableList<PieChart.Data> pieChartData = FXCollections.observableArrayList();
        double total = mentions.values().stream().mapToInt(Integer::intValue).sum();

        if (total > 0) {
            mentions.forEach((ticker, count) -> {
                double percentage = (count / total) * 100.0;
                String label = i18n.get("widget.ticker_chart.format", ticker, percentage);
                pieChartData.add(new PieChart.Data(label, count));
            });
        }

        pieChart.setData(pieChartData);
        // Toggle placeholder: visible only when no data
        boolean hasData = !pieChartData.isEmpty();
        pieChart.setVisible(hasData);
        pieChart.setManaged(hasData);
        emptyLabel.setVisible(!hasData);
        emptyLabel.setManaged(!hasData);
    }

    @Override
    protected Node topPane() {
        SVGPath logo = new SVGPath();
        logo.setContent(PIE_SVG);
        logo.setFill(Color.web("#00bcd4"));
        logo.setScaleX(0.75);
        logo.setScaleY(0.75);

        HBox header = new HBox(6, logo, titleLabel);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(8, 12, 6, 12));
        return header;
    }

    @Override
    protected Node centerPane() {
        StackPane container = new StackPane(pieChart, emptyLabel);
        container.setAlignment(Pos.CENTER);
        VBox.setVgrow(pieChart, Priority.ALWAYS);

        // Initial state: show placeholder until first data arrives
        pieChart.setVisible(false);
        pieChart.setManaged(false);

        return container;
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

    public void shutdown() {
        eventBus.unregister(this);
    }
}
