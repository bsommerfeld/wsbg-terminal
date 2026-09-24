package de.bsommerfeld.signals;

import java.util.function.BiConsumer;

/**
 * Implemented by the generated subclass of every view that declares signals:
 * it overrides each signal method and reports the call once the method has run.
 * Not for hand-written classes.
 */
public interface SignalEmitter {

    /**
     * Sends this view's signals to {@code sink} from now on. Once; a signal
     * sent before - from the constructor - is refused.
     */
    void connectSignals(BiConsumer<SignalStub<?, ?>, Object> sink);
}
