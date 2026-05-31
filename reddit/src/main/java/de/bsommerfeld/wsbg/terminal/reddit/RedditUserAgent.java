package de.bsommerfeld.wsbg.terminal.reddit;

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.Properties;

/**
 * The Reddit User-Agent string, shared by every transport.
 *
 * <p>
 * Reddit's convention is {@code <platform>:<app-id>:<version> (by /u/<user>)};
 * requests with a generic or missing User-Agent are throttled harder. The
 * version is injected from {@code reddit-version.properties} at build time via
 * Maven resource filtering, falling back to "unknown" for IDE-only runs.
 *
 * <p>
 * A short random {@code instance} token is appended so two installs don't share
 * an identical User-Agent. This matters most on the anonymous {@code .json} and
 * RSS paths: Reddit throttles per IP+UA <em>pair</em>, so a per-install token
 * keeps each user on their own budget instead of pooling everyone under one
 * heavily-rate-limited string. The token is regenerated per process — it
 * identifies the running instance, not the human.
 */
public final class RedditUserAgent {

    /** The fully-formed User-Agent value. */
    public static final String VALUE = build();

    private RedditUserAgent() {}

    private static String build() {
        String version = "unknown";
        try (InputStream in = RedditUserAgent.class.getResourceAsStream("/reddit-version.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                version = props.getProperty("app.version", "unknown");
            }
        } catch (IOException e) {
            // Fall back to "unknown" — a missing version must not prevent startup
        }
        return "java:de.bsommerfeld.wsbg.terminal:v" + version
                + " (by /u/WsbgTerminal; instance:" + randomInstanceToken() + ")";
    }

    /** 8 hex characters of randomness — unique enough to split the UA budget. */
    private static String randomInstanceToken() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(Integer.toHexString(random.nextInt(16)));
        }
        return sb.toString();
    }
}
