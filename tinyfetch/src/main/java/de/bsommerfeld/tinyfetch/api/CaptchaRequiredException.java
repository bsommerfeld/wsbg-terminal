package de.bsommerfeld.tinyfetch.api;

/**
 * The site wants a person to solve a CAPTCHA, and none did - there was no
 * {@link CaptchaSolver}, the person declined, or closed the window unsolved.
 * The host is paused like after any {@link Wall#CHALLENGE}.
 */
public final class CaptchaRequiredException extends FetchException {

    private final String host;

    public CaptchaRequiredException(String host, String message) {
        super(host + ": " + message);
        this.host = host;
    }

    public String host() {
        return host;
    }
}
