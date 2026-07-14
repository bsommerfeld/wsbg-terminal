package de.bsommerfeld.wsbg.terminal.source.net;

import com.google.inject.BindingAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link WebFetcher} injection point for a keyless API WITHOUT a bot
 * wall (Consorsbank, onvista, Tradegate, BaFin, Google News, the briefing
 * clients, the article digester).
 *
 * <p><b>Since 2026-07-14 this binding is an ALIAS of the browser-first chain</b>
 * (user mandate: every third-party outreach rides the browser joker, plain
 * HTTP strictly as the per-request fallback when the joker can't answer) —
 * see {@code NetModule.provideDirectFirstWebFetcher}. The annotation survives
 * as the policy seam: clients keep declaring "I'd be fine on plain HTTP", so
 * a future revert to a genuine direct-first chain is one provider change,
 * not thirty call sites.
 */
@BindingAnnotation
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface DirectFirst {
}
