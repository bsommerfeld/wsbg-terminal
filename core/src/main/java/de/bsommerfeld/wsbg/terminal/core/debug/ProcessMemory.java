package de.bsommerfeld.wsbg.terminal.core.debug;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Resident set size of arbitrary processes — the only honest answer to "how
 * much of this machine is the app holding". The JVM's own beans cannot give it:
 * the heap is a fraction of the JVM's footprint, and Ollama is a foreign
 * process entirely, so both numbers have to come from the OS.
 *
 * <p><strong>Unix only, on purpose.</strong> {@code ps} answers for every pid in
 * one call, which is what makes this cheap enough to poll. Windows has no
 * equivalent one-shot that is worth spawning a PowerShell for, so the probe
 * reports "unavailable" there and the caller renders the gap instead of a lie.
 * This is debug tooling (dev-mode only), so the platform gap costs nothing.
 */
public final class ProcessMemory {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");

    private ProcessMemory() {
    }

    /** Whether this platform answers {@link #rssBytes} at all. */
    public static boolean available() {
        return !WINDOWS;
    }

    /**
     * RSS in bytes per pid. Pids that died between listing and probing are simply
     * absent from the map — never zero, which would read as "uses no memory".
     *
     * @return pid → resident bytes; empty on Windows or when {@code ps} fails
     */
    public static Map<Long, Long> rssBytes(Collection<Long> pids) {
        Map<Long, Long> out = new LinkedHashMap<>();
        if (WINDOWS || pids == null || pids.isEmpty()) return out;
        String list = pids.stream().map(String::valueOf).collect(Collectors.joining(","));
        try {
            Process p = new ProcessBuilder("ps", "-o", "pid=,rss=", "-p", list)
                    .redirectErrorStream(false)
                    .start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length < 2) continue;
                    try {
                        // ps reports RSS in kibibytes on both macOS and Linux.
                        out.put(Long.parseLong(parts[0]), Long.parseLong(parts[1]) * 1024L);
                    } catch (NumberFormatException ignored) {
                        // header or a locale-mangled row — skip it, keep the rest
                    }
                }
            }
            if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // no ps, no permission — the caller renders "unavailable"
        }
        return out;
    }
}
