package de.bsommerfeld.signals;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One level of the hierarchy signals travel up through. A signal sent into a
 * scope goes to the handler this scope binds for it; if there is none, on to
 * the enclosing scope, and so on up to the root, which hands it to its
 * {@link UnhandledSignalHandler}. The nearest binding wins, and it is the only
 * one to hear the signal.
 *
 * <p>Confined to one thread, like the UI it usually serves.
 */
public final class SignalScope implements SignalEmitter {

    private final Map<SignalKey<?>, Consumer<Object>> handlers = new HashMap<>();
    private final Escalation escalation;

    private SignalScope(Escalation escalation) {
        this.escalation = escalation;
    }

    /** The outermost scope: what reaches it unbound goes to {@code unhandled}. */
    public static SignalScope root(UnhandledSignalHandler unhandled) {
        Objects.requireNonNull(unhandled, "unhandled");
        return new SignalScope(unhandled::unhandled);
    }

    /**
     * A scope inside another. The enclosing scope is asked for each time a
     * signal is passed on, not once: a hierarchy that is still forming or
     * changes shape - a scene graph - is followed as it is at that moment.
     *
     * @param enclosing never returns {@code null}; the root has no enclosing
     *                  scope of its own
     */
    public static SignalScope nested(Supplier<SignalScope> enclosing) {
        Objects.requireNonNull(enclosing, "enclosing");
        return new SignalScope((key, payload) -> enclosing.get().dispatch(key, payload));
    }

    /**
     * Handles {@code key} in this scope and below it.
     *
     * @throws IllegalStateException if this scope binds {@code key} already
     */
    @SuppressWarnings("unchecked")
    public <P> void bind(SignalKey<P> key, Consumer<? super P> handler) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(handler, "handler");
        if (handlers.putIfAbsent(key, payload -> handler.accept((P) payload)) != null) {
            throw new IllegalStateException("this scope binds " + key + " already");
        }
    }

    /** Handles a signal without payload in this scope and below it. */
    public void bind(SignalKey<Void> key, Runnable handler) {
        Objects.requireNonNull(handler, "handler");
        bind(key, ignored -> handler.run());
    }

    @Override
    public <P> void emit(SignalKey<P> key, P payload) {
        dispatch(Objects.requireNonNull(key, "key"), payload);
    }

    private void dispatch(SignalKey<?> key, Object payload) {
        Consumer<Object> handler = handlers.get(key);
        if (handler != null) {
            handler.accept(payload);
        } else {
            escalation.escalate(key, payload);
        }
    }

    /** Where a signal goes that this scope does not bind. */
    @FunctionalInterface
    private interface Escalation {
        void escalate(SignalKey<?> key, Object payload);
    }
}
