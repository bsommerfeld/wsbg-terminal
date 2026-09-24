package de.bsommerfeld.wsbg.terminal.fx;

import de.bsommerfeld.signals.SignalScope;
import de.bsommerfeld.signals.Signals;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;

/**
 * A node whose markup is an FXML file and whose state is a {@link ViewModel}.
 * The class is the root of its markup and its controller in one: the
 * constructor resolves the view model from the injector and loads
 * {@code SimpleName.fxml} from beside the class into the node, which is why the
 * markup begins with {@code <fx:root type="...">} and names no
 * {@code fx:controller}. {@code SimpleName.css} beside the class is attached if
 * there is one, and the kebab-cased class name is added as style class.
 *
 * <p>{@code initialize()} runs inside this constructor, before the subclass's
 * own field initialisers: only {@link #viewModel} and the {@code @FXML} fields
 * are set by then. State belongs in the view model, not in the node.
 *
 * <p>The view model hears {@link ViewModel#onAttach()} when the node joins a
 * scene and {@link ViewModel#onDetach()} when it leaves one.
 *
 * <p>Every node is a {@link SignalScope}. The view model's
 * {@link de.bsommerfeld.signals.Signal @Signal} methods report into it, and
 * what the node does not bind travels up the scene graph to the next
 * {@code FxmlNode} - the one that embeds it - and finally to the root scope.
 * A node binds the signals of what it embeds in {@link #signals()}, typically
 * in {@code initialize()}:
 * <pre>{@code
 * signals().bind(HeadlineCardViewModelSignals.opened(), viewModel::openInstrument);
 * }</pre>
 *
 * <p>A node is a component unless it is annotated {@link View @View}. The root
 * is a {@link StackPane}.
 *
 * @param <M> the view model; spelled out in the {@code extends} clause, that is
 *            where it is read from
 */
public abstract class FxmlNode<M extends ViewModel> extends StackPane {

    protected final M viewModel;
    private final SignalScope signals = SignalScope.nested(this::enclosingScope);

    protected FxmlNode() {
        Class<M> modelType = FxmlReflections.viewModelType(getClass());
        viewModel = Fx.injector().getInstance(Signals.implementationOf(modelType));
        Signals.connect(viewModel, signals);
        FxmlReflections.inflate(this);
        sceneProperty().addListener((_, previous, current) -> followScene(previous, current));
    }

    public final M viewModel() {
        return viewModel;
    }

    /** Where this node binds the signals of its own view model and of what it embeds. */
    protected final SignalScope signals() {
        return signals;
    }

    /** The nearest {@code FxmlNode} above this one in the scene graph, or the root scope. */
    private SignalScope enclosingScope() {
        for (Parent parent = getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof FxmlNode<?> node) {
                return node.signals;
            }
        }
        return Fx.injector().getInstance(SignalScope.class);
    }

    private void followScene(Scene previous, Scene current) {
        if (previous == null && current != null) {
            viewModel.onAttach();
        } else if (previous != null && current == null) {
            viewModel.onDetach();
        }
    }
}
