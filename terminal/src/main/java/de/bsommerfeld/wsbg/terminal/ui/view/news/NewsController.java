package de.bsommerfeld.wsbg.terminal.ui.view.news;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import jakarta.inject.Inject;
import javafx.fxml.FXML;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.util.Callback;

public class NewsController {

    @FXML
    private ListView<RedditThread> newsList;

    private final NewsViewModel viewModel;
    private final ApplicationEventBus eventBus;

    @Inject
    public NewsController(NewsViewModel viewModel, ApplicationEventBus eventBus) {
        this.viewModel = viewModel;
        this.eventBus = eventBus;
    }

    @FXML
    public void initialize() {
        // Bind List
        newsList.setItems(viewModel.getThreads());

        // Custom Cell Factory
        newsList.setCellFactory(param -> new ListCell<>() {
            @Override
            protected void updateItem(RedditThread item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(String.format("[%s] %s (Score: %d)", item.getSubreddit(), item.getTitle(),
                            item.getScore()));
                    // Simple styling
                    setStyle("-fx-text-fill: white; -fx-padding: 5;");
                }
            }
        });

        // Click Listener for Analysis
        newsList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                viewModel.analyzeSentiment(newVal);
            }
        });

        // Initial Fetch
        viewModel.refreshNews();
    }
}
