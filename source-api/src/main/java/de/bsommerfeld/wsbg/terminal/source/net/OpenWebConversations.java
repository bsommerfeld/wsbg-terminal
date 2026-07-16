package de.bsommerfeld.wsbg.terminal.source.net;

import com.google.inject.BindingAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the {@link WebFetcher} that answers OpenWeb (Spot.IM) conversation
 * reads — Yahoo Finance's per-ticker comment boards (2026-07-16).
 *
 * <p>This is NOT a plain transport: the provider behind it runs the widget's
 * own anonymous handshake inside a hidden browser tab anchored on
 * finance.yahoo.com (authenticate → bearer token → conversation/read as
 * page-context POSTs, so Origin, CORS and cookies match the real widget).
 * Callers hand it a conversation-read URL carrying {@code spotId} and
 * {@code postId} query parameters and get the conversation JSON back; when
 * the browser runtime is unavailable the binding degrades to a fetcher that
 * answers a transport failure (status 0) — the source yields empty, never
 * breaks.
 */
@BindingAnnotation
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface OpenWebConversations {
}
