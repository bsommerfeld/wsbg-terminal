package de.bsommerfeld.signals;

/**
 * Implemented by the generated subclass of every class that declares signals.
 * Not for hand-written classes; reached through {@link Signals#connect}.
 */
public interface Signaling {

    /** Sends this instance's signals to {@code emitter} from now on. Once. */
    void connectSignals(SignalEmitter emitter);
}
