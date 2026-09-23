/**
 * Methods as signals. A class declares what can happen to it as methods
 * annotated {@link de.bsommerfeld.signals.Signal @Signal}; whoever encloses an
 * instance binds those signals in a {@link de.bsommerfeld.signals.SignalScope}
 * and decides what they mean. The declaring class never learns who listens.
 *
 * <p>The same jar is the annotation processor: on the processor path it
 * generates, per declaring class, the keys to bind and the subclass that
 * reports each call. The processor package is not exported - it only ever runs
 * inside the compiler, which is also why {@code java.compiler} is needed at
 * compile time alone.
 */
module de.bsommerfeld.signals {
    requires static java.compiler;

    exports de.bsommerfeld.signals;

    provides javax.annotation.processing.Processor with de.bsommerfeld.signals.processor.SignalProcessor;
}
