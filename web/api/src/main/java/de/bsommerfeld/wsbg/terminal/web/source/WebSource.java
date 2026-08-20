package de.bsommerfeld.wsbg.terminal.web.source;

import de.bsommerfeld.wsbg.terminal.web.article.SourceOrigin;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;

/**
 * The one contract every outbound web source implements — collector, search
 * engine or instrument source alike. A source describes ITSELF (name, origin,
 * transport order, nature); nothing about it is curated anywhere else. The
 * role is defined by which sub-interface a class implements, not by which
 * package it lives in.
 */
public interface WebSource {

    /** Short, stable identifier for attribution/logging (e.g. {@code "yahoo"}). */
    String sourceName();

    /**
     * The transport order this source's requests ride —
     * {@code {DIRECT}}, {@code {BROWSER}} or both; the array order IS the
     * fallback order. Declared once per source, resolved by the house fetcher.
     */
    FetchUtil[] mode();

    /**
     * Where this source's items come FROM — language and press sphere. The
     * pipeline stamps every article a source hands back with this. A source
     * that cannot say keeps the default.
     */
    default SourceOrigin origin() {
        return SourceOrigin.UNKNOWN;
    }

    /**
     * {@code true} when this source carries SOCIAL SENTIMENT (forum posts,
     * social-network chatter — opinion from a room) rather than reported news.
     * The pool keeps the two streams apart: sentiment never masquerades as an
     * article in the press loom, and press never answers the sentiment fan.
     */
    default boolean socialSentiment() {
        return false;
    }
}
