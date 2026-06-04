package de.bsommerfeld.wsbg.terminal.core.util;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

/**
 * Cheap, cached TCP-reachability probe for a single host:port.
 *
 * <p>
 * Used as an "is the internet (and specifically this endpoint) up?" gate so
 * that callers can skip a network operation cleanly when offline instead of
 * eating a full connect-timeout per request. The probe is a bare TCP connect
 * (no TLS handshake, no HTTP) with a short timeout, so an offline verdict
 * comes back fast (DNS failure / connection refused are near-instant) and a
 * working connection costs one handshake.
 *
 * <p>
 * The verdict is cached for {@code cacheTtl} so a burst of calls (e.g. one
 * editorial tick resolving several subjects against Yahoo) probes at most
 * once per window. Both online and offline verdicts are cached and re-probed
 * after the TTL, so connectivity loss <em>and</em> recovery are picked up
 * within one window. A benign race where two threads probe simultaneously is
 * harmless — they just write the same field.
 *
 * <p>
 * Pure and dependency-free (no logging framework) so it can live in
 * {@code core} and be reused anywhere.
 */
public final class HostReachability {

    private final String host;
    private final int port;
    private final int probeTimeoutMillis;
    private final long cacheTtlMillis;

    private volatile boolean lastVerdict;
    /** Epoch millis of the last probe; {@code 0} means "never probed". */
    private volatile long lastProbeAt;

    /**
     * @param host          host to probe (e.g. {@code query1.finance.yahoo.com})
     * @param port          port to connect to (e.g. {@code 443})
     * @param probeTimeout  how long a single connect attempt may take before it
     *                      counts as unreachable
     * @param cacheTtl      how long a verdict is reused before the next probe
     */
    public HostReachability(String host, int port, Duration probeTimeout, Duration cacheTtl) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        this.host = host;
        this.port = port;
        this.probeTimeoutMillis = (int) Math.max(1, probeTimeout.toMillis());
        this.cacheTtlMillis = Math.max(0, cacheTtl.toMillis());
    }

    /**
     * Returns whether the host appears reachable, using a cached verdict when
     * one is still fresh and probing otherwise.
     */
    public boolean isReachable() {
        long now = System.currentTimeMillis();
        long at = lastProbeAt;
        if (at != 0 && now - at < cacheTtlMillis) {
            return lastVerdict;
        }
        boolean verdict = probe();
        lastVerdict = verdict;
        lastProbeAt = now;
        return verdict;
    }

    private boolean probe() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), probeTimeoutMillis);
            return true;
        } catch (IOException | RuntimeException e) {
            // UnknownHostException, ConnectException, timeout, or a security
            // manager refusal all mean "treat as offline".
            return false;
        }
    }
}
