package de.bsommerfeld.wsbg.terminal.fx;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.Scopes;

import java.util.Objects;
import java.util.stream.Stream;

/**
 * The injector, reachable from anywhere a node is constructed. Nodes are made
 * by {@code new} and by FXMLLoader, outside of any injection, so this is the
 * one static in the layer: set once at startup, read by {@link FxmlNode}.
 */
public final class Fx {

    private static volatile Injector injector;

    private Fx() {
    }

    /**
     * Creates the injector from the layer's own bindings and the application's
     * modules. Once; a second call is a programming error.
     */
    public static void init(Module... modules) {
        Objects.requireNonNull(modules, "modules");
        if (injector != null) {
            throw new IllegalStateException("Fx is already initialised");
        }
        injector = Guice.createInjector(Stream.concat(Stream.of(new Layer()), Stream.of(modules)).toList());
    }

    public static boolean initialized() {
        return injector != null;
    }

    public static Injector injector() {
        Injector current = injector;
        if (current == null) {
            throw new IllegalStateException("Fx.init() has not been called");
        }
        return current;
    }

    /** What the layer itself needs: {@link View @View} as a singleton scope. */
    private static final class Layer extends AbstractModule {

        @Override
        protected void configure() {
            bindScope(View.class, Scopes.SINGLETON);
        }
    }
}
