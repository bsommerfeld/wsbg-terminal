package de.bsommerfeld.signals;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method of a view whose call is a signal. After the method has run,
 * the call goes up to the {@link ViewRegister}, and the register decides what
 * it means - the view never learns who hears it.
 *
 * <pre>{@code
 * @Signal
 * public void checkHeadline(Headline headline) { ... }
 *
 * // in the register
 * wire(signal(DashboardView::new).checkHeadline())
 *         .to(HeadlineCheckView.class, (view, headline) -> view.inspect(headline));
 * }</pre>
 *
 * <p>A signal method is overridable (not private, static or final), returns
 * void and takes at most one parameter - several values go into a record -,
 * and its name is its own within the class. The class is public, not final,
 * not abstract, not generic, and has a public constructor. The processor
 * rejects everything else at compile time.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Signal {

    SignalType value() default SignalType.CHANGE_VIEW;
}
