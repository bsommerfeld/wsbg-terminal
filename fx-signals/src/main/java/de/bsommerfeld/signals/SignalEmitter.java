package de.bsommerfeld.signals;

/** Where a signal goes when its method has been called. */
public interface SignalEmitter {

    /**
     * Sends the signal {@code key} with its payload.
     *
     * @param payload the signal method's argument, {@code null} for a method
     *                without one
     */
    <P> void emit(SignalKey<P> key, P payload);
}
