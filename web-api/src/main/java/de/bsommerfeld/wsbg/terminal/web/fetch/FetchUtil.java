package de.bsommerfeld.wsbg.terminal.web.fetch;

/**
 * The transports a source may ride. A source declares an ORDERED array via
 * {@link de.bsommerfeld.wsbg.terminal.web.source.WebSource#mode()} and the
 * generic fetcher tries them left to right until one answers definitively —
 * the array IS the fallback order, there is no other wiring.
 */
public enum FetchUtil {

    /** Plain HTTP client — fast, headless, no session. */
    DIRECT,

    /**
     * The embedded browser "joker" — requests ride a real Chromium session
     * with cookies and a genuine fingerprint, which passes walls (Cloudflare,
     * consent gates) a bare client cannot. Expensive; order it second unless
     * the host is known to wall plain clients.
     */
    BROWSER
}
