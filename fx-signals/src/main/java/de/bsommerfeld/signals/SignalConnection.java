package de.bsommerfeld.signals;

import java.util.Objects;

/**
 * For generated code: the link from one signalling instance to where its
 * signals go. Connected once, after the instance is constructed; a signal
 * method called before that - from a constructor - is refused.
 */
public final class SignalConnection {

    private final Class<?> owner;
    private SignalEmitter emitter;

    public SignalConnection(Class<?> owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    /** @throws IllegalStateException if the instance is connected already */
    public void connect(SignalEmitter emitter) {
        Objects.requireNonNull(emitter, "emitter");
        if (this.emitter != null) {
            throw new IllegalStateException("this " + owner.getSimpleName() + " is connected already");
        }
        this.emitter = emitter;
    }

    /** @throws IllegalStateException if the instance is not connected yet */
    public <P> void emit(SignalKey<P> key, P payload) {
        if (emitter == null) {
            throw new IllegalStateException(key + " was sent before its " + owner.getSimpleName()
                    + " was connected - signals cannot be sent from a constructor");
        }
        emitter.emit(key, payload);
    }
}
