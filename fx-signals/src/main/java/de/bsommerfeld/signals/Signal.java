package de.bsommerfeld.signals;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method whose call is a signal: something that happened, named by the
 * method. After the method has run, the call is reported to the nearest
 * {@link SignalScope} that binds it, with the argument as payload.
 *
 * <p>The processor generates {@code OwnerSignals} beside the declaring class:
 * one static accessor per signal, returning the {@link SignalKey} to bind.
 * <pre>{@code
 * class CalculatorViewModel {
 *     @Signal void resultShown(Result result) {}
 * }
 *
 * scope.bind(CalculatorViewModelSignals.resultShown(), result -> ...);
 * }</pre>
 *
 * <p>A signal method is overridable (not private, static or final), returns
 * void and takes at most one parameter - several values go into a record. Its
 * class is a concrete, non-final, non-generic top-level or static nested
 * class, and every signal in it has a name of its own. The processor rejects
 * everything else at compile time.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Signal {
}
