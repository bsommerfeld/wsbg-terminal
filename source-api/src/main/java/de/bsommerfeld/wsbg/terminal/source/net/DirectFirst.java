package de.bsommerfeld.wsbg.terminal.source.net;

import com.google.inject.BindingAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link WebFetcher} injection point that wants the DIRECT transport
 * first, with the embedded-browser "joker" only as the per-request fallback —
 * the right order for keyless APIs WITHOUT a bot wall (Consorsbank, onvista,
 * Tradegate, BaFin, Bundesanzeiger, Google News): the plain client answers
 * 200 every time, so the hidden Chromium is never touched, its per-origin tabs
 * never spawn, and the joker's capacity stays reserved for the hosts that
 * actually need a real browser fingerprint (Reddit, Yahoo, NASDAQ, CNN — the
 * unannotated browser-first chain). If such a host ever grows a bot wall, the
 * chain degrades gracefully: the direct 403 falls through to the browser.
 */
@BindingAnnotation
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface DirectFirst {
}
