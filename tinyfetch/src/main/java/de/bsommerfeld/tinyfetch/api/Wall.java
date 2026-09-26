package de.bsommerfeld.tinyfetch.api;

/**
 * How a host answered in terms of access - whether it let the request through
 * or put something in the way. Every {@link FetchResponse} carries one, and
 * every wall sends its host into a cooldown (see {@link HostPolicy}).
 */
public enum Wall {

    /** Let through. */
    NONE,

    /**
     * Asked to slow down: {@code 429}, or {@code 503} with {@code Retry-After}.
     * The host is paused for {@code Retry-After}, at least the policy's
     * throttle back-off.
     */
    THROTTLED,

    /**
     * {@code 403} without a challenge page - refused, reason unstated. Treated
     * like {@link #THROTTLED}: a script that keeps knocking after a refusal is
     * how a temporary block turns into a lasting one.
     */
    FORBIDDEN,

    /**
     * A page that wants proof of a human: a CAPTCHA, a JavaScript challenge,
     * a "blocked by network security" notice - whatever the status code.
     * TinyFetch never tries to solve one. The host is paused for the policy's
     * challenge back-off, which is long on purpose: the block is usually on
     * the IP, and every further request confirms the suspicion.
     */
    CHALLENGE
}
