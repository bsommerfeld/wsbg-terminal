package de.bsommerfeld.tinyfetch.curl;

import java.util.Locale;

/**
 * The machine TinyFetch runs on: the OS decides the library's file name and
 * what the browser headers claim, OS and architecture together name the
 * {@code .native/} directory. Names match TinyUpdate's platforms,
 * {@code {macos,windows,linux}-{x86_64,aarch64}}.
 */
public enum NativePlatform {

    MACOS("macos", "libcurl-impersonate.dylib"),
    WINDOWS("windows", "libcurl-impersonate.dll"),
    LINUX("linux", "libcurl-impersonate.so");

    private final String osName;
    private final String libraryFileName;

    NativePlatform(String osName, String libraryFileName) {
        this.osName = osName;
        this.libraryFileName = libraryFileName;
    }

    /** The OS this JVM runs on; anything unrecognised is treated as Linux. */
    public static NativePlatform current() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return MACOS;
        }
        if (os.contains("win")) {
            return WINDOWS;
        }
        return LINUX;
    }

    /** {@code macos-aarch64}, {@code windows-x86_64}, ... */
    public static String currentId() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String normalized = arch.equals("arm64") || arch.equals("aarch64") ? "aarch64" : "x86_64";
        return current().osName + "-" + normalized;
    }

    public String libraryFileName() {
        return libraryFileName;
    }
}
