package de.bsommerfeld.wsbg.terminal.fx;

import com.google.inject.TypeLiteral;
import de.bsommerfeld.signals.SignalEmitter;
import javafx.fxml.FXMLLoader;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URL;
import java.util.Locale;

/** What {@link FxmlNode} does behind its constructor: finding the view model type and loading the markup. */
final class FxmlReflections {

    private FxmlReflections() {
    }

    /**
     * The view model type named in {@code extends FxmlNode<...>}, resolved
     * through any generic classes in between ({@code Framed<M> extends FxmlNode<M>}).
     */
    @SuppressWarnings("unchecked")
    static <M extends ViewModel> Class<M> viewModelType(Class<?> node) {
        Type supertype = TypeLiteral.get(node).getSupertype(FxmlNode.class).getType();
        if (supertype instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments()[0] instanceof Class<?> model) {
            return (Class<M>) model;
        }
        throw new IllegalStateException(node.getName()
                + ": spell the view model out as a class, 'extends FxmlNode<...>'");
    }

    /**
     * Loads {@code SimpleName.fxml} from beside the node's class into the node,
     * which is root and controller, then attaches {@code SimpleName.css} if
     * there is one and adds the kebab-cased class name as style class. A view
     * with signals is built as its generated subclass; the names are those of
     * the view as written.
     *
     * @throws IllegalStateException if the FXML is missing or does not load
     */
    static void inflate(FxmlNode<?> node) {
        Class<?> type = node instanceof SignalEmitter ? node.getClass().getSuperclass() : node.getClass();
        String fxml = type.getSimpleName() + ".fxml";
        URL location = type.getResource(fxml);
        if (location == null) {
            throw new IllegalStateException(type.getName() + ": " + fxml + " not found beside the class");
        }
        FXMLLoader loader = new FXMLLoader(location);
        loader.setRoot(node);
        loader.setController(node);
        try {
            loader.load();
        } catch (IOException e) {
            throw new IllegalStateException(
                    type.getName() + ": " + fxml + " did not load" + hint(e) + ": " + e.getMessage(), e);
        }
        URL css = type.getResource(type.getSimpleName() + ".css");
        if (css != null) {
            node.getStylesheets().add(css.toExternalForm());
        }
        node.getStyleClass().add(styleClass(type));
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
}
