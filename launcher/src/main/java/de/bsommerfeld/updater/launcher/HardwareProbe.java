package de.bsommerfeld.updater.launcher;

import java.lang.management.ManagementFactory;

/**
 * One-shot probe of the machine the terminal is about to run on. Feeds the
 * model recommendation ({@link ModelCatalog}) — deliberately coarse: total
 * physical memory plus the platform split is enough to place a machine on the
 * gemma4 ladder. VRAM detection (discrete GPUs on Windows/Linux) is a known
 * v2 candidate; total RAM is the conservative v1 signal, and on Apple Silicon
 * unified memory IS the GPU memory anyway.
 *
 * @param totalMemoryBytes total physical memory, or {@code 0} if unprobeable
 * @param osName           {@code os.name} verbatim
 * @param osArch           {@code os.arch} verbatim
 */
record HardwareProbe(long totalMemoryBytes, String osName, String osArch) {

    static HardwareProbe probe() {
        long mem = 0;
        try {
            // jdk.management is in the jlink image (ALL-MODULE-PATH). Guarded
            // anyway: an exotic JVM without it must never break the launcher.
            if (ManagementFactory.getOperatingSystemMXBean()
                    instanceof com.sun.management.OperatingSystemMXBean sun) {
                mem = sun.getTotalMemorySize();
            }
        } catch (Throwable ignored) {
        }
        return new HardwareProbe(mem,
                System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""));
    }

    /**
     * Total memory rounded to the nearest GB. Rounding (not truncation) matters:
     * firmware/OS reservations make a 16 GB machine report slightly under
     * 16 GiB, which floor division would misread as a 15 GB machine.
     */
    long totalMemoryGb() {
        return Math.round(totalMemoryBytes / (double) (1L << 30));
    }

    boolean isMac() {
        return osName.toLowerCase().contains("mac");
    }

    /** Apple Silicon = the MLX builds apply. Intel Macs stay on the base tags. */
    boolean isAppleSilicon() {
        String arch = osArch.toLowerCase();
        return isMac() && (arch.contains("aarch64") || arch.contains("arm64"));
    }
}
