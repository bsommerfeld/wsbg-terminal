package de.bsommerfeld.wsbg.terminal.ui;

import de.bsommerfeld.wsbg.terminal.core.i18n.I18nService;
import javafx.beans.property.BooleanProperty;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;

import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Glassmorphism settings popover anchored to the title bar.
 * Pure UI — all state and persistence are handled by
 * {@link SettingsViewModel}. Controls bind bidirectionally
 * against the ViewModel's observable properties.
 *
 * <p>
 * The popover rebuilds its content on language changes
 * so all labels reflect the active locale immediately.
 */
public class SettingsPopOver {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsPopOver.class);

    private static final Duration ANIM_IN = Duration.millis(280);
    private static final Duration ANIM_OUT = Duration.millis(180);
    private static final Interpolator SPRING = Interpolator.SPLINE(0.2, 0.9, 0.1, 1.0);

    private static final Map<String, String> LANGUAGE_LABELS = Map.of(
            "de", "Deutsch",
            "en", "English");

    private final SettingsViewModel viewModel;
    private final I18nService i18n;

    private StackPane overlay;
    private VBox popover;
    private boolean visible;
    private Node lastAnchor;

    SettingsPopOver(SettingsViewModel viewModel) {
        this.viewModel = viewModel;
        this.i18n = viewModel.i18n();

        // Rebuild the popover UI when the language changes on-the-fly
        viewModel.setOnLanguageChanged(() -> {
            if (visible && lastAnchor != null) {
                rebuildContent();
            }
        });
    }

    /** Toggles the popover below the given anchor node. */
    void toggle(Node anchor) {
        if (visible) {
            hide();
        } else {
            show(anchor);
        }
    }

    boolean isVisible() {
        return visible;
    }

    // ── Show / Hide ─────────────────────────────────────────────────

    private void show(Node anchor) {
        StackPane root = findShellRoot(anchor);
        if (root == null) {
            LOG.warn("Cannot find StackPane shell root for popover");
            return;
        }

        lastAnchor = anchor;
        popover = buildContent();

        overlay = new StackPane();
        overlay.setStyle("-fx-background-color: transparent;");
        overlay.setPickOnBounds(true);
        overlay.setAlignment(Pos.TOP_RIGHT);
        overlay.setOnMousePressed(e -> {
            if (!popover.getBoundsInParent().contains(e.getX(), e.getY())) {
                hide();
            }
        });

        Bounds anchorBounds = anchor.localToScene(anchor.getBoundsInLocal());
        double topMargin = anchorBounds.getMaxY() + 8;
        double rightMargin = root.getWidth() - anchorBounds.getMaxX();
        StackPane.setMargin(popover, new Insets(topMargin, Math.max(4, rightMargin - 20), 0, 0));

        overlay.getChildren().add(popover);
        root.getChildren().add(overlay);
        visible = true;
        animateIn(popover);
    }

    private void hide() {
        if (!visible || overlay == null)
            return;
        visible = false;
        animateOut(popover, () -> {
            if (overlay.getParent() instanceof Pane parent) {
                parent.getChildren().remove(overlay);
            }
        });
    }

    /**
     * Replaces the popover content in-place to reflect a locale change.
     * Preserves the overlay and position — only the inner VBox is swapped.
     */
    private void rebuildContent() {
        if (overlay == null)
            return;
        VBox fresh = buildContent();
        Insets margin = StackPane.getMargin(popover);
        StackPane.setMargin(fresh, margin);

        // Swap without animation — instant refresh
        fresh.setOpacity(1.0);
        overlay.getChildren().set(0, fresh);
        popover = fresh;
    }

    // ── Content assembly ────────────────────────────────────────────

    private VBox buildContent() {
        VBox content = new VBox(0);
        content.getStyleClass().add("settings-popover");
        content.setMaxWidth(400);
        content.setMinWidth(360);
        content.setPickOnBounds(false);

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(content.widthProperty());
        clip.heightProperty().bind(content.heightProperty());
        clip.setArcWidth(24);
        clip.setArcHeight(24);
        content.setClip(clip);

        Label header = new Label("::  " + i18n.get("settings.title"));
        header.getStyleClass().add("settings-header");

        // Banner container — always above cards, stacks vertically
        VBox bannerBox = new VBox(4);
        bannerBox.getStyleClass().add("settings-banner-box");
        bannerBox.setPadding(new Insets(0, 12, 0, 12));
        syncBanners(bannerBox);

        VBox cards = new VBox(2);
        cards.setPadding(new Insets(0, 12, 12, 12));
        cards.getChildren().addAll(
                buildAgentCard(),
                buildHeadlineCard(),
                buildUserCard());

        content.getChildren().addAll(header, bannerBox, cards);
        return content;
    }

    /**
     * Keeps the banner VBox in sync with the ViewModel's observable banner list.
     */
    private void syncBanners(VBox bannerBox) {
        for (SettingsViewModel.Banner banner : viewModel.banners()) {
            bannerBox.getChildren().add(buildBannerNode(banner));
        }
        viewModel.banners().addListener((ListChangeListener<SettingsViewModel.Banner>) c -> {
            while (c.next()) {
                if (c.wasAdded()) {
                    for (SettingsViewModel.Banner b : c.getAddedSubList()) {
                        bannerBox.getChildren().add(buildBannerNode(b));
                    }
                }
                if (c.wasRemoved()) {
                    for (SettingsViewModel.Banner b : c.getRemoved()) {
                        bannerBox.getChildren().removeIf(node -> b.message().equals(node.getUserData()));
                    }
                }
            }
        });
    }

    private Node buildBannerNode(SettingsViewModel.Banner banner) {
        HBox bannerNode = new HBox(8);
        bannerNode.getStyleClass().add("settings-banner");
        bannerNode.setAlignment(Pos.CENTER_LEFT);
        bannerNode.setUserData(banner.message());
        bannerNode.setCursor(Cursor.HAND);

        Label icon = new Label("!");
        icon.getStyleClass().add("settings-banner-icon");

        Label text = new Label(banner.message());
        text.getStyleClass().add("settings-banner-text");
        HBox.setHgrow(text, Priority.ALWAYS);

        Label action = new Label(banner.action() == SettingsViewModel.Banner.Action.UPDATE
                ? i18n.get("settings.banner.update_now")
                : ">");
        action.getStyleClass().add("settings-banner-action");

        // Both banner types launch the full update pipeline
        bannerNode.setOnMouseClicked(e -> viewModel.restartViaLauncher(true));

        bannerNode.getChildren().addAll(icon, text, action);
        return bannerNode;
    }

    // ── Agent Card ──────────────────────────────────────────────────

    private Node buildAgentCard() {
        VBox card = glassCard();
        Label title = sectionTitle("[>] " + i18n.get("settings.section.agent"));

        Node toggle = pillToggle(viewModel.powerModeProperty());

        HBox row = settingRow(
                i18n.get("settings.agent.power_mode"),
                i18n.get("settings.agent.power_mode.hint"),
                toggle);

        card.getChildren().addAll(title, row);
        return card;
    }

    // ── Headline Card ───────────────────────────────────────────────

    private Node buildHeadlineCard() {
        VBox card = glassCard();
        Label title = sectionTitle("[#] " + i18n.get("settings.section.headlines"));

        Node enabledToggle = pillToggle(viewModel.headlinesEnabledProperty());
        HBox enabledRow = settingRow(
                i18n.get("settings.headlines.enabled"),
                i18n.get("settings.headlines.enabled.hint"),
                enabledToggle);

        Node showAllToggle = pillToggle(viewModel.headlinesShowAllProperty());
        HBox showAllRow = settingRow(
                i18n.get("settings.headlines.show_all"),
                i18n.get("settings.headlines.show_all.hint"),
                showAllToggle);

        // Cascade: disable "show all" when headlines are off
        showAllRow.disableProperty().bind(viewModel.headlinesEnabledProperty().not());

        // Topic chips
        Label topicsLabel = new Label(i18n.get("settings.headlines.topics"));
        topicsLabel.getStyleClass().add("settings-label");

        FlowPane chipPane = new FlowPane(6, 6);
        chipPane.getStyleClass().add("settings-chip-pane");

        TextField topicInput = new TextField();
        topicInput.setPromptText(i18n.get("settings.headlines.topics.prompt"));
        topicInput.getStyleClass().add("settings-topics-field");

        // Initial chips from existing topics
        String existing = viewModel.headlinesTopicsProperty().get();
        if (existing != null && !existing.isBlank()) {
            Arrays.stream(existing.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(topic -> chipPane.getChildren().add(
                            buildChip(topic, chipPane)));
        }

        // ENTER or "," commits a new chip
        topicInput.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                commitChip(topicInput, chipPane);
                e.consume();
            } else if (e.getCode() == KeyCode.BACK_SPACE
                    && topicInput.getText().isEmpty()
                    && !chipPane.getChildren().isEmpty()) {
                chipPane.getChildren().remove(chipPane.getChildren().size() - 1);
                syncTopicsToViewModel(chipPane);
            }
        });

        topicInput.textProperty().addListener((obs, o, n) -> {
            if (n != null && n.contains(",")) {
                String cleaned = n.replace(",", "").trim();
                topicInput.setText("");
                if (!cleaned.isEmpty()) {
                    chipPane.getChildren().add(buildChip(cleaned, chipPane));
                    syncTopicsToViewModel(chipPane);
                }
            }
        });

        topicInput.setOnAction(e -> commitChip(topicInput, chipPane));

        VBox topicsBox = new VBox(4, topicsLabel, chipPane, topicInput);

        // Cascade: disable topics when headlines are off OR "show all" is on
        topicsBox.disableProperty().bind(
                viewModel.headlinesEnabledProperty().not()
                        .or(viewModel.headlinesShowAllProperty()));

        card.getChildren().addAll(title, enabledRow, showAllRow, topicsBox);
        return card;
    }

    private void commitChip(TextField input, FlowPane chipPane) {
        String text = input.getText().trim();
        if (text.isEmpty())
            return;
        input.setText("");
        chipPane.getChildren().add(buildChip(text, chipPane));
        syncTopicsToViewModel(chipPane);
    }

    private Node buildChip(String text, FlowPane chipPane) {
        HBox chip = new HBox(4);
        chip.getStyleClass().add("settings-chip");
        chip.setAlignment(Pos.CENTER);

        Label label = new Label(text);
        label.getStyleClass().add("settings-chip-label");

        Label remove = new Label("x");
        remove.getStyleClass().add("settings-chip-remove");
        remove.setCursor(Cursor.HAND);
        remove.setOnMouseClicked(e -> {
            chipPane.getChildren().remove(chip);
            syncTopicsToViewModel(chipPane);
        });

        chip.getChildren().addAll(label, remove);
        return chip;
    }

    private void syncTopicsToViewModel(FlowPane chipPane) {
        String topics = chipPane.getChildren().stream()
                .filter(n -> n instanceof HBox)
                .map(n -> ((Label) ((HBox) n).getChildren().get(0)).getText())
                .collect(Collectors.joining(", "));
        viewModel.headlinesTopicsProperty().set(topics);
    }

    // ── User Card ───────────────────────────────────────────────────

    private Node buildUserCard() {
        VBox card = glassCard();
        Label title = sectionTitle("[@] " + i18n.get("settings.section.user"));

        // Language combo with display labels
        ComboBox<String> langBox = new ComboBox<>(
                FXCollections.observableArrayList("de", "en"));
        langBox.valueProperty().bindBidirectional(viewModel.languageProperty());
        langBox.getStyleClass().add("settings-combo");
        langBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(String code) {
                return LANGUAGE_LABELS.getOrDefault(code, code);
            }

            @Override
            public String fromString(String string) {
                return LANGUAGE_LABELS.entrySet().stream()
                        .filter(e -> e.getValue().equals(string))
                        .map(Map.Entry::getKey)
                        .findFirst()
                        .orElse(string);
            }
        });
        langBox.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : LANGUAGE_LABELS.getOrDefault(item, item));
            }
        });
        HBox langRow = settingRow(i18n.get("settings.user.language"), null, langBox);

        // Auto-update toggle
        Node autoUpdateToggle = pillToggle(viewModel.autoUpdateProperty());
        HBox autoUpdateRow = settingRow(
                i18n.get("settings.user.auto_update"),
                i18n.get("settings.user.auto_update.hint"),
                autoUpdateToggle);

        card.getChildren().addAll(title, langRow, autoUpdateRow);
        return card;
    }

    // ── Reusable components ─────────────────────────────────────────

    private static VBox glassCard() {
        VBox card = new VBox(8);
        card.getStyleClass().add("settings-glass-card");
        card.setPadding(new Insets(14, 16, 14, 16));
        return card;
    }

    private static Label sectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("settings-section-title");
        return label;
    }

    private static HBox settingRow(String labelText, String subtitle, Node control) {
        VBox labelBox = new VBox(1);
        Label lbl = new Label(labelText);
        lbl.getStyleClass().add("settings-label");
        labelBox.getChildren().add(lbl);

        if (subtitle != null) {
            Label sub = new Label(subtitle);
            sub.getStyleClass().add("settings-hint");
            sub.setWrapText(true);
            sub.setMaxWidth(180);
            labelBox.getChildren().add(sub);
        }

        HBox row = new HBox();
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("settings-row");
        HBox.setHgrow(labelBox, Priority.ALWAYS);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        row.getChildren().addAll(labelBox, spacer, control);
        return row;
    }

    /**
     * Builds a custom pill-shaped toggle switch.
     * JavaFX ToggleButton lacks an internal thumb node, so pure CSS
     * cannot produce a pill switch — we build it from raw Regions.
     */
    private static StackPane pillToggle(BooleanProperty property) {
        double trackW = 38, trackH = 22, thumbSize = 16;
        double travel = trackW - thumbSize - 6;

        StackPane track = new StackPane();
        track.setMinSize(trackW, trackH);
        track.setMaxSize(trackW, trackH);
        track.setPrefSize(trackW, trackH);
        track.setCursor(Cursor.HAND);
        track.setAlignment(Pos.CENTER_LEFT);

        Region thumb = new Region();
        thumb.setMinSize(thumbSize, thumbSize);
        thumb.setMaxSize(thumbSize, thumbSize);
        thumb.setTranslateX(3);
        thumb.setStyle("-fx-background-color: #555; -fx-background-radius: 8;");

        track.getChildren().add(thumb);

        Runnable updateVisual = () -> {
            if (property.get()) {
                track.setStyle(
                        "-fx-background-color: rgba(255, 159, 0, 0.3);"
                                + "-fx-background-radius: 11;"
                                + "-fx-border-color: rgba(255, 159, 0, 0.45);"
                                + "-fx-border-radius: 11; -fx-border-width: 1;");
                thumb.setStyle("-fx-background-color: #ff9f00; -fx-background-radius: 8;");
                thumb.setTranslateX(travel);
            } else {
                track.setStyle(
                        "-fx-background-color: rgba(255, 255, 255, 0.06);"
                                + "-fx-background-radius: 11;"
                                + "-fx-border-color: rgba(255, 255, 255, 0.1);"
                                + "-fx-border-radius: 11; -fx-border-width: 1;");
                thumb.setStyle("-fx-background-color: #555; -fx-background-radius: 8;");
                thumb.setTranslateX(3);
            }
        };

        updateVisual.run();
        property.addListener((obs, o, n) -> updateVisual.run());
        track.setOnMouseClicked(e -> property.set(!property.get()));

        return track;
    }

    // ── Animations ──────────────────────────────────────────────────

    private static void animateIn(Node node) {
        node.setOpacity(0);
        node.setScaleX(0.92);
        node.setScaleY(0.92);
        node.setTranslateY(-12);

        FadeTransition fade = new FadeTransition(ANIM_IN, node);
        fade.setToValue(1.0);
        fade.setInterpolator(SPRING);

        ScaleTransition scale = new ScaleTransition(ANIM_IN, node);
        scale.setToX(1.0);
        scale.setToY(1.0);
        scale.setInterpolator(SPRING);

        TranslateTransition slide = new TranslateTransition(ANIM_IN, node);
        slide.setToY(0);
        slide.setInterpolator(SPRING);

        new ParallelTransition(fade, scale, slide).play();
    }

    private static void animateOut(Node node, Runnable onFinished) {
        FadeTransition fade = new FadeTransition(ANIM_OUT, node);
        fade.setToValue(0.0);
        fade.setInterpolator(Interpolator.EASE_IN);

        ScaleTransition scale = new ScaleTransition(ANIM_OUT, node);
        scale.setToX(0.95);
        scale.setToY(0.95);
        scale.setInterpolator(Interpolator.EASE_IN);

        TranslateTransition slide = new TranslateTransition(ANIM_OUT, node);
        slide.setToY(-8);
        slide.setInterpolator(Interpolator.EASE_IN);

        ParallelTransition parallel = new ParallelTransition(fade, scale, slide);
        parallel.setOnFinished(e -> onFinished.run());
        parallel.play();
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static StackPane findShellRoot(Node node) {
        Node current = node;
        while (current != null) {
            if (current instanceof StackPane sp && sp.getStyleClass().contains("window-shell-root")) {
                return sp;
            }
            if (current.getParent() == null && current instanceof StackPane sp) {
                return sp;
            }
            current = current.getParent();
        }
        return null;
    }
}
