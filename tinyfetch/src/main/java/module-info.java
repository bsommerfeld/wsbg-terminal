/**
 * TinyFetch: HTTP that is one person's browser. Every request is the
 * {@code fetch()} of a real Chromium page parked on the target's site, so it
 * carries that browser's fingerprint, cookies and session; requests go out one
 * at a time per host at a human pace and stop as soon as a host signals it
 * wants less - see {@link de.bsommerfeld.tinyfetch.api.TinyFetch} for the rules.
 *
 * <p>Chromium runs in its own JVM, TinyBrowser, never in the application's
 * process. {@code de.bsommerfeld.tinyfetch.engine} is the protocol between the
 * two; TinyBrowser reads it from the class path, so it is not exported.
 */
module de.bsommerfeld.tinyfetch {
    exports de.bsommerfeld.tinyfetch.api;
}
