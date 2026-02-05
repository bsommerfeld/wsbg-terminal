package de.bsommerfeld.wsbg.terminal.ui.view.dock.widgets;

import com.google.common.eventbus.Subscribe;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.TickerSnapshotEvent;
import de.bsommerfeld.wsbg.terminal.core.i18n.I18nService;
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
import javafx.scene.layout.VBox;

import java.util.Map;

/**
 * Displays a Pie Chart showing the distribution of mentions for different
 * financial instruments (stocks, ETFs, etc.) based on AI extractions.
 */
public class TickerChartWidget extends DockWidget {

    public static final String IDENTIFIER = "ticker-chart";

    private final ApplicationEventBus eventBus;
    private final I18nService i18n;
    private final PieChart pieChart;
    private final Label titleLabel;

    public TickerChartWidget(ApplicationEventBus eventBus, I18nService i18n) {
        this.eventBus = eventBus;
        this.i18n = i18n;
        this.pieChart = new PieChart();
        this.pieChart.setLegendVisible(true);
        this.pieChart.setLabelsVisible(true);
        this.pieChart.setAnimated(true);
        
        // Disable simple legend if percentages are shown in labels
        this.pieChart.setLegendVisible(false);

        this.titleLabel = new Label(i18n.get("widget.ticker_chart.title"));
        this.titleLabel.getStyleClass().add("fj-title");

        buildWidget();
        eventBus.register(this);
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
                // Format label as requested "Das ganze in %" mapped to resource bundle
                String label = i18n.get("widget.ticker_chart.format", ticker, percentage);
                pieChartData.add(new PieChart.Data(label, count));
            });
        }

        pieChart.setData(pieChartData);
    }

    @Override
    protected Node topPane() {
        HBox header = new HBox(titleLabel);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(8, 12, 6, 12));
        return header;
    }

    @Override
    protected Node centerPane() {
        VBox container = new VBox(pieChart);
        container.setAlignment(Pos.CENTER);
        VBox.setVgrow(pieChart, Priority.ALWAYS);
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

    /**
     * Unregister from EventBus when shutting down widget
     */
    public void shutdown() {
        eventBus.unregister(this);
    }
}
