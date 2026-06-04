package de.bsommerfeld.updater.api;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Tiny "do we have internet?" check for the launcher's update phase.
 *
 * <p>
 * A bare TCP connect to GitHub's API host with a short timeout. The point is
 * to fail <em>fast</em> when offline: without it, the first update download
 * blocks for the full 30 s connect timeout before the launcher can fall back
 * to the cached install. With it, an offline user reaches their installed
 * version in a couple of seconds.
 *
 * <p>
 * No TLS, no HTTP — a successful TCP handshake is enough signal that the
 * network is up and GitHub is reachable. Any failure (DNS, refused, timeout)
 * is treated as offline.
 */
public final class ConnectivityProbe {

    /** GitHub REST API host — the same host the update check talks to. */
    private static final String DEFAULT_HOST = "api.github.com";
    private static final int HTTPS_PORT = 443;
    private static final int DEFAULT_TIMEOUT_MILLIS = 3000;

    private ConnectivityProbe() {
    }

    /** @return {@code true} if GitHub's API host is reachable over TCP. */
    public static boolean isOnline() {
        return isReachable(DEFAULT_HOST, HTTPS_PORT, DEFAULT_TIMEOUT_MILLIS);
    }

    /**
     * Attempts a TCP connect to {@code host:port}.
     *
     * @return {@code true} on a successful handshake, {@code false} on any
     *         failure (unresolvable host, refused, timeout, …)
     */
    public static boolean isReachable(String host, int port, int timeoutMillis) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
