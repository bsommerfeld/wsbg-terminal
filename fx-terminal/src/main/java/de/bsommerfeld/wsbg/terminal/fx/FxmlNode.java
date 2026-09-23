package de.bsommerfeld.wsbg.terminal.fx;

import de.bsommerfeld.signals.SignalScope;
import de.bsommerfeld.signals.Signals;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

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
        Class<M> modelType = viewModelType(getClass());
        viewModel = Fx.injector().getInstance(Signals.implementationOf(modelType));
        Signals.connect(viewModel, signals);
        inflate();
        sceneProperty().addListener((observable, previous, current) -> followScene(previous, current));
    }

    public final M viewModel() {
        return viewModel;
    }

    /** Where this node binds the signals of its own view model and of what it embeds. */
    protected final SignalScope signals() {
        return signals;
    }

    /** @throws IllegalStateException if the FXML is missing or does not load */
    private void inflate() {
        Class<?> type = getClass();
        String fxml = type.getSimpleName() + ".fxml";
        URL location = type.getResource(fxml);
        if (location == null) {
            throw new IllegalStateException(type.getName() + ": " + fxml + " not found beside the class");
        }
        FXMLLoader loader = new FXMLLoader(location);
        loader.setRoot(this);
        loader.setController(this);
        try {
            loader.load();
        } catch (IOException e) {
            throw new IllegalStateException(
                    type.getName() + ": " + fxml + " did not load" + hint(e) + ": " + e.getMessage(), e);
        }
        URL css = type.getResource(type.getSimpleName() + ".css");
        if (css != null) {
            getStylesheets().add(css.toExternalForm());
        }
        getStyleClass().add(styleClass(type));
    }

    /** Turns the loader's two most likely complaints into what to change in the markup. */
    private static String hint(IOException e) {
        String message = String.valueOf(e.getMessage());
        if (message.contains("Controller value already specified")) {
            return " - drop fx:controller from the markup, the class is the controller";
        }
        if (message.contains("Root value already specified")) {
            return " - the markup must begin with <fx:root type=\"...\">, the class is the root";
        }
        return "";
    }

    /** {@code HeadlineList} → {@code headline-list}, {@code FGDetail} → {@code fg-detail}. */
    static String styleClass(Class<?> type) {
        return type.getSimpleName()
                .replaceAll("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])", "-")
                .toLowerCase(Locale.ROOT);
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

    /**
     * The view model type named in {@code extends FxmlNode<...>}, read through
     * any intermediate generic classes in between: walking up from the node,
     * every superclass's type arguments are bound to its type parameters, so a
     * variable passed along ({@code Framed<M> extends FxmlNode<M>}) resolves
     * to what the subclass filled in.
     */
    @SuppressWarnings("unchecked")
    private static <M extends ViewModel> Class<M> viewModelType(Class<?> node) {
        Map<TypeVariable<?>, Type> bindings = new HashMap<>();
        for (Class<?> type = node; type != FxmlNode.class; type = type.getSuperclass()) {
            if (!(type.getGenericSuperclass() instanceof ParameterizedType superclass)) {
                if (type.getSuperclass() == FxmlNode.class) {
                    throw new IllegalStateException(node.getName()
                            + ": spell the view model out, 'extends FxmlNode<...>'");
                }
                continue;
            }
            bind(superclass, bindings);
        }
        Type model = bindings.get(FxmlNode.class.getTypeParameters()[0]);
        if (model instanceof Class<?> modelClass) {
            return (Class<M>) modelClass;
        }
        throw new IllegalStateException(node.getName()
                + ": the view model in 'extends FxmlNode<...>' must be a class, not " + model);
    }

    /** Binds the superclass's type parameters to its arguments, resolving arguments that are bound already. */
    private static void bind(ParameterizedType superclass, Map<TypeVariable<?>, Type> bindings) {
        TypeVariable<?>[] parameters = ((Class<?>) superclass.getRawType()).getTypeParameters();
        Type[] arguments = superclass.getActualTypeArguments();
        for (int i = 0; i < parameters.length; i++) {
            Type argument = arguments[i];
            if (argument instanceof TypeVariable<?> variable && bindings.containsKey(variable)) {
                argument = bindings.get(variable);
            }
            bindings.put(parameters[i], argument);
        }
    }
}
