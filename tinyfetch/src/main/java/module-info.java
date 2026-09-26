/**
 * TinyFetch: HTTP that looks and behaves like one person using a browser.
 * Requests carry a real browser's TLS and HTTP/2 fingerprint, go out one at a
 * time per host at a human pace, and stop as soon as a host signals it wants
 * less - see {@link de.bsommerfeld.tinyfetch.api.TinyFetch} for the rules.
 *
 * <p>Underneath is libcurl-impersonate, bound through FFM; the module therefore
 * needs {@code --enable-native-access=de.bsommerfeld.tinyfetch}.
 */
module de.bsommerfeld.tinyfetch {
    exports de.bsommerfeld.tinyfetch.api;
}
