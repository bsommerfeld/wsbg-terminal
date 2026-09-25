package de.bsommerfeld.wsbg.terminal.chrome;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HeaderBar;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * The window's content: the {@link TitleBar} over the frame, and in the frame
 * the view that is shown - 5px of ground to the window edge.
 *
 * <p>Zen mode takes the chrome away: the bar slides up out of the window, the
 * frame closes, the view grows into the whole window, and the system window
 * buttons go with the bar. The pointer at the top edge brings the bar back
 * over the view - to move the window by it, or to leave zen mode through the
 * same button; once the pointer leaves it, the bar goes again. Resizing is the
 * window's own edge and is not touched.
 */
public final class Shell extends StackPane {

    /** The ground between the view and the window edge. */
    private static final double FRAME = 5;

    /** How close to the top edge the pointer has to come to bring the bar back. */
    private static final double REVEAL_EDGE = 4;

    private static final Duration ZEN_DURATION = Duration.millis(320);
    private static final Duration REVEAL_DURATION = Duration.millis(180);
    private static final Duration HIDE_DELAY = Duration.millis(350);
    private static final PseudoClass ZEN = PseudoClass.getPseudoClass("zen");

    private static final Interpolator EASE_OUT = Interpolator.SPLINE(0.2, 0, 0, 1);

    private final Stage stage;
    private final BooleanProperty zen = new SimpleBooleanProperty(this, "zen");
    private final TitleBar titleBar = new TitleBar(zen);
    /**
     * The bar's shadow over the view in zen mode, on a node of its own behind
     * the bar: as an effect on the bar itself, every repaint inside it - the
     * zen switch under the pointer - leaves a smear in the shadow below.
     */
    private final Region titleBarShadow = new Region();
    private final StackPane frame = new StackPane();

    /** 0 framed, 1 zen: the layout follows this, the switch only sets where it runs to. */
    private final DoubleProperty zenProgress = new SimpleDoubleProperty(this, "zenProgress");
    /** 0 bar away, 1 bar over the view; only has an effect in zen mode. */
    private final DoubleProperty revealProgress = new SimpleDoubleProperty(this, "revealProgress");
    private boolean revealed;

    private Timeline zenMotion = new Timeline();
    private Timeline revealMotion = new Timeline();
    private final PauseTransition hideLater = new PauseTransition(HIDE_DELAY);

    public Shell(Stage stage) {
        this.stage = stage;
        getStyleClass().add("shell");
        frame.getStyleClass().add("frame");
        // For the view to follow: in zen mode it meets the window edge.
        zen.subscribe(on -> frame.pseudoClassStateChanged(ZEN, on));

        titleBarShadow.getStyleClass().add("titlebar-shadow");
        titleBarShadow.setMouseTransparent(true);
        titleBarShadow.visibleProperty().bind(zen);
        titleBarShadow.translateYProperty().bind(titleBar.translateYProperty());

        StackPane.setAlignment(titleBarShadow, Pos.TOP_CENTER);
        StackPane.setAlignment(titleBar, Pos.TOP_CENTER);
        getChildren().addAll(frame, titleBarShadow, titleBar);
        titleBar.followSystemButtons(stage);

        frame.paddingProperty().bind(Bindings.createObjectBinding(() -> {
            double framed = 1 - zenProgress.get();
            double edge = FRAME * framed;
            return new Insets(TitleBar.HEIGHT * framed, edge, edge, edge);
        }, zenProgress));
        titleBar.translateYProperty().bind(Bindings.createDoubleBinding(
                () -> -TitleBar.HEIGHT * zenProgress.get() * (1 - revealProgress.get()),
                zenProgress, revealProgress));

        zen.addListener((_, _, on) -> switchZen(on));
        hideLater.setOnFinished(_ -> reveal(false));
        addEventFilter(MouseEvent.MOUSE_MOVED, this::pointerMoved);
    }

    /** Shows a view in the frame, in place of the one before. */
    public void show(Node view) {
        frame.getChildren().setAll(view);
    }

    /** The title bar's update notice - hidden until an update is offered. */
    public UpdateNotice updateNotice() {
        return titleBar.updateNotice();
    }

    public BooleanProperty zenProperty() {
        return zen;
    }

    private void switchZen(boolean on) {
        hideLater.stop();
        // A reveal still under way would set the system buttons when it ends, over what is set here.
        revealMotion.stop();
        if (on) {
            // The pointer is on the button that was just clicked: the bar goes now, not when it leaves.
            revealed = false;
            revealProgress.set(0);
            systemButtons(false);
        } else {
            systemButtons(true);
        }

        zenMotion.stop();
        zenMotion = motion(zenProgress, on ? 1 : 0, ZEN_DURATION);
        zenMotion.setOnFinished(_ -> {
            if (!on) {
                // Only now: while the frame opens, the bar has to stay where the reveal put it.
                revealed = false;
                revealProgress.set(0);
            }
        });
        zenMotion.play();
    }

    private void pointerMoved(MouseEvent e) {
        if (!zen.get()) {
            return;
        }
        double y = e.getSceneY();
        if (y <= REVEAL_EDGE) {
            hideLater.stop();
            reveal(true);
        } else if (revealed && y > TitleBar.HEIGHT) {
            if (hideLater.getStatus() != Animation.Status.RUNNING) {
                hideLater.playFromStart();
            }
        } else {
            hideLater.stop();
        }
    }

    private void reveal(boolean show) {
        if (revealed == show) {
            return;
        }
        revealed = show;
        if (!show) {
            systemButtons(false);
        }

        revealMotion.stop();
        revealMotion = motion(revealProgress, show ? 1 : 0, REVEAL_DURATION);
        // The system buttons are not ours to animate: they come once the bar is there to hold them.
        revealMotion.setOnFinished(_ -> systemButtons(revealed));
        revealMotion.play();
    }

    private void systemButtons(boolean visible) {
        HeaderBar.setSystemButtonHeight(stage, visible ? TitleBar.HEIGHT : 0);
    }

    /** Runs {@code property} from wherever it is now to {@code target}. */
    private static Timeline motion(DoubleProperty property, double target, Duration duration) {
        return new Timeline(new KeyFrame(duration, new KeyValue(property, target, EASE_OUT)));
    }
}
