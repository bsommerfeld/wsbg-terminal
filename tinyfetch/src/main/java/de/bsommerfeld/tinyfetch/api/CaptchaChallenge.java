package de.bsommerfeld.tinyfetch.api;

import java.net.URI;
import java.util.Objects;

/**
 * A CAPTCHA a host answered with - what a {@link CaptchaSolver} is asked about.
 *
 * @param host   the host that wants a person, lower-case - the one now paused
 * @param url    the address that answered with the challenge
 * @param status its HTTP status - a challenge comes with any, a plain 200 included
 */
public record CaptchaChallenge(String host, URI url, int status) {

    public CaptchaChallenge {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(url, "url");
    }
}
