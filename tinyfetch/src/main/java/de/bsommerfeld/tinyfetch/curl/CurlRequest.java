package de.bsommerfeld.tinyfetch.curl;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Everything one transfer needs, already decided: the header lines arrive in
 * their final order, because the order is part of the fingerprint and libcurl
 * sends them as given.
 *
 * @param impersonateTarget libcurl-impersonate target, e.g. {@code chrome150}
 * @param acceptEncoding    what the headers announce - libcurl must decode exactly that
 * @param body              request body, {@code null} for none
 * @param caBundle          PEM bundle to trust instead of the library's default, or {@code null}
 * @param cancelled         polled during the transfer; {@code true} aborts it
 */
public record CurlRequest(
        String url,
        String method,
        List<String> headerLines,
        byte[] body,
        String impersonateTarget,
        String acceptEncoding,
        Duration timeout,
        Duration connectTimeout,
        long maxBodyBytes,
        Path caBundle,
        BooleanSupplier cancelled) {
}
