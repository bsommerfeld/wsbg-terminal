package de.bsommerfeld.tinysearch.api;

import java.util.Objects;

/**
 * How one engine fared in one search.
 *
 * @param engine  the engine asked
 * @param outcome what came of it
 * @param hits    how many hits its page held; 0 unless {@link Outcome#ANSWERED}
 * @param detail  why it did not answer, for logs; empty when it did
 */
public record EngineReport(SearchEngine engine, Outcome outcome, int hits, String detail) {

    public EngineReport {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(detail, "detail");
        if (hits < 0 || hits > 0 && outcome != Outcome.ANSWERED) {
            throw new IllegalArgumentException(outcome + " with " + hits + " hits");
        }
    }

    public enum Outcome {

        /** Its result page came back; with 0 hits it found nothing. */
        ANSWERED,

        /**
         * It put a wall up - throttle, refusal, CAPTCHA. TinyFetch pauses its
         * host, so the next searches find it {@link #PAUSED}.
         */
        REFUSED,

        /**
         * Not asked: its host is paused after a wall, or the engine is left
         * alone after an {@link #UNREADABLE} page.
         */
        PAUSED,

        /**
         * An answer that is not a result page - a check TinyFetch does not
         * know as a wall, or a page the engine has rebuilt. The engine is
         * left alone for a while: every further request to a check confirms
         * the suspicion.
         */
        UNREADABLE,

        /** No usable answer: network, timeout, an error status. */
        FAILED
    }
}
