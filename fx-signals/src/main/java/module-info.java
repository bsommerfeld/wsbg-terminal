/**
 * Signals between views that do not know each other. A view marks methods
 * {@link de.bsommerfeld.signals.Signal @Signal}; a call goes up to the
 * {@link de.bsommerfeld.signals.ViewRegister}, which alone knows the views and
 * decides where the signal goes.
 *
 * <p>The same jar is the annotation processor: on the processor path it
 * generates, per view, the stubs to wire and the subclass that reports each
 * call, and one {@code Signals} class over all views. The processor package is
 * not exported - it only ever runs inside the compiler, which is also why
 * {@code java.compiler} is needed at compile time alone.
 */
module de.bsommerfeld.signals {
    requires static java.compiler;

    exports de.bsommerfeld.signals;

    provides javax.annotation.processing.Processor with de.bsommerfeld.signals.processor.SignalProcessor;
}
