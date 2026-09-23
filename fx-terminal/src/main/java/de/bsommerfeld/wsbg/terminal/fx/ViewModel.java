package de.bsommerfeld.wsbg.terminal.fx;

/**
 * The state behind an {@link FxmlNode}: observable properties the markup binds
 * to, and the services they are fed from. Services are constructor-injected;
 * the node never sees them.
 *
 * <p>The hooks follow the node in and out of a scene. Whatever
 * {@link #onAttach()} subscribes to, {@link #onDetach()} lets go of - a
 * component's model is thrown away with the component, and a listener it left
 * on a service keeps it alive.
 */
public interface ViewModel {

    /** The node has joined a scene. */
    default void onAttach() {
    }

    /** The node has left its scene. */
    default void onDetach() {
    }
}
