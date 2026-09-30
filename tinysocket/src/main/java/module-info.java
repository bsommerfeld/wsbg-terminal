/**
 * TinySocket: WebSockets that are one person's browser. Every socket is
 * Chromium's own, opened in a hidden tab of TinyBrowser - the engine TinyFetch
 * runs, and shares - see {@link de.bsommerfeld.tinysocket.api.TinySocket}.
 */
module de.bsommerfeld.tinysocket {
    requires transitive de.bsommerfeld.tinyfetch;

    exports de.bsommerfeld.tinysocket.api;
}
