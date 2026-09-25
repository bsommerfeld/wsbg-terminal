package de.bsommerfeld.updater;

import de.bsommerfeld.tinyupdate.api.UpdateProgress;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.HeaderBar;
import javafx.scene.layout.HeaderDragType;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The updater's window content: the application's name in the header bar,
 * below it what is happening (headline), how far (progress bar) and the
 * particulars (step, speed). A failure turns the bar red and brings the two
 * ways out - try again, or close.
 */
final class UpdaterView extends BorderPane {

    private static final PseudoClass FAILED = PseudoClass.getPseudoClass("failed");

    private final Messages messages;
    private final String applicationName;

    private final Label headline = new Label();
    private final ProgressBar bar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
    private final Label detail = new Label();
    private final Button retry = new Button();
    private final Button close = new Button();
    private final HBox actions = new HBox(close, retry);

    /** The last speed reported - TinyUpdate sends {@link UpdateProgress#SPEED_UNCHANGED} between measurements. */
    private long speed = -1;

    UpdaterView(Messages messages, String applicationName, Runnable onRetry, Runnable onClose) {
        this.messages = messages;
        this.applicationName = applicationName;
        getStyleClass().add("updater");

        Label title = new Label(applicationName);
        title.getStyleClass().add("title");
        HeaderBar header = new HeaderBar();
        header.getStyleClass().add("header");
        header.setCenter(title);
        HeaderBar.setDragType(title, HeaderDragType.TRANSPARENT);
        setTop(header);

        headline.getStyleClass().add("headline");
        detail.getStyleClass().add("detail");
        bar.setMaxWidth(Double.MAX_VALUE);

        retry.setText(messages.get("retry"));
        retry.getStyleClass().add("primary");
        retry.setDefaultButton(true);
        retry.setOnAction(_ -> onRetry.run());
        close.setText(messages.get("close"));
        close.setOnAction(_ -> onClose.run());
        actions.getStyleClass().add("actions");
        actions.setAlignment(Pos.CENTER_RIGHT);
        showActions(false);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        VBox body = new VBox(headline, bar, detail, spacer, actions);
        body.getStyleClass().add("body");
        setCenter(body);
    }

    void show(UpdateStatus status) {
        pseudoClassStateChanged(FAILED, status instanceof UpdateStatus.Failed);
        showActions(status instanceof UpdateStatus.Failed);

        switch (status) {
            case UpdateStatus.Waiting() -> indeterminate(messages.get("waiting", applicationName));
            case UpdateStatus.Updating(UpdateProgress progress) -> progress(progress);
            case UpdateStatus.Finishing() -> indeterminate(messages.get("finishing"));
            case UpdateStatus.Relaunching() -> indeterminate(messages.get("relaunching", applicationName));
            case UpdateStatus.Failed(UpdateStatus.Failure failure, String reason) -> failed(
                    messages.get("failure." + failure.name(), reason));
        }
    }

    /** The updater was started without a handoff it could read: nothing to retry. */
    void showInvalid() {
        failed(messages.get("invalid"));
        pseudoClassStateChanged(FAILED, true);
        actions.getChildren().setAll(close);
        showActions(true);
    }

    private void progress(UpdateProgress progress) {
        headline.setText(messages.phase(progress.phase()));
        bar.setProgress(progress.progressRatio() < 0 ? ProgressBar.INDETERMINATE_PROGRESS : progress.progressRatio());

        if (progress.speedBytesPerSec() != UpdateProgress.SPEED_UNCHANGED) {
            speed = progress.speedBytesPerSec();
        }
        StringBuilder text = new StringBuilder();
        if (progress.step() > 0) {
            text.append(messages.get("step", progress.step(), progress.totalSteps()));
        }
        if (speed > 0 && progress.progressRatio() >= 0) {
            text.append(text.isEmpty() ? "" : "  ·  ").append(messages.speed(speed));
        }
        detail.setText(text.toString());
    }

    private void indeterminate(String text) {
        headline.setText(text);
        bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        detail.setText("");
        speed = -1;
    }

    private void failed(String reason) {
        headline.setText(messages.get("failed"));
        bar.setProgress(1);
        detail.setText(reason);
    }

    private void showActions(boolean visible) {
        actions.setVisible(visible);
        actions.setManaged(visible);
    }
}
