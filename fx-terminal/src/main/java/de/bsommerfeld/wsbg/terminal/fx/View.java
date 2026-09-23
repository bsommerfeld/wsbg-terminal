package de.bsommerfeld.wsbg.terminal.fx;

import com.google.inject.ScopeAnnotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an {@link FxmlNode} as a view: one instance for the whole application,
 * built by the injector on first request and kept - a scope, bound as singleton
 * by {@link Fx}. Its view model lives as long as it does.
 *
 * <p>A node without it is a component: instantiated as often as it is used, by
 * {@code new} or as a tag in another node's FXML, every instance with a view
 * model of its own.
 */
@Documented
@ScopeAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface View {
}
