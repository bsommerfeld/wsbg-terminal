package de.bsommerfeld.tinyupdate.api;

import java.util.Locale;

/**
 * The platform identifier a platform stream is published under
 * ({@link ReleaseAssetNames#of}): {@code <os>-<arch>}, with the os one of
 * {@code macos}, {@code windows}, {@code linux} and the arch one of
 * {@code x86_64}, {@code aarch64}.
 *
 * <p>
 * The release workflow names its per-platform assets with exactly these
 * strings, so the two must never drift apart.
 */
public final class Platform {

    private Platform() {
    }

    /** The platform this JVM runs on. */
    public static String current() {
        return of(System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    /**
     * The identifier for an {@code os.name} / {@code os.arch} pair.
     *
     * @throws IllegalStateException for an operating system or architecture no
     *                               stream is published for
     */
    static String of(String osName, String osArch) {
        String name = osName.toLowerCase(Locale.ROOT);
        String os;
        if (name.startsWith("mac")) {
            os = "macos";
        } else if (name.startsWith("windows")) {
            os = "windows";
        } else if (name.startsWith("linux")) {
            os = "linux";
        } else {
            throw new IllegalStateException("Unsupported operating system: " + osName);
        }
        String arch = switch (osArch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> throw new IllegalStateException("Unsupported architecture: " + osArch);
        };
        return os + "-" + arch;
    }
}
