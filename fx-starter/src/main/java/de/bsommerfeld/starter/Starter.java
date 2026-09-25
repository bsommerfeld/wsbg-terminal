package de.bsommerfeld.starter;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * The entry point of a native launcher: seeds the installs this launcher is
 * responsible for, then boots its install ({@link StarterConfig}).
 *
 * <p>
 * No network, no window, nothing that waits: the starter does in a few
 * milliseconds what has to happen before the application can run, and
 * leaves updates to the application.
 *
 * <p>
 * For the application it leaves two system properties behind - the contract
 * by which an application knows it runs installed, and where:
 *
 * <pre>
 * starter.data.dir     the application's data directory
 * starter.install.dir  the install that was booted
 * </pre>
 */
public final class Starter {

    /** The application's data directory, set for the booted application. */
    public static final String DATA_DIR = "starter.data.dir";

    /** The booted install, set for the booted application. */
    public static final String INSTALL_DIR = "starter.install.dir";

    private Starter() {
    }

    static void main(String[] arguments) throws Throwable {
        StarterConfig config = StarterConfig.from(System.getProperties());
        Files.createDirectories(config.dataDirectory());
        redirectConsole(config);

        seed(config);

        System.setProperty(DATA_DIR, config.dataDirectory().toString());
        System.setProperty(INSTALL_DIR, config.installDirectory().toString());
        ModuleBoot.run(config.installDirectory().resolve("lib"), config.mainModule(), config.mainClass(),
                config.nativeAccess(), arguments);
    }

    /**
     * Lays out every install this launcher seeds. Only the booted install is
     * required: a failure there leaves nothing to start and ends the run,
     * while one elsewhere is logged and left to the next start.
     */
    private static void seed(StarterConfig config) throws IOException {
        if (config.seed() == null) {
            return;
        }
        for (String install : config.seedInstalls()) {
            try {
                if (Seeder.seed(config.seed(), config.dataDirectory().resolve(install))) {
                    log("Seeded " + install + " from " + config.seed());
                }
            } catch (IOException e) {
                if (install.equals(config.install())) {
                    throw e;
                }
                log("Could not seed " + install + ": " + e);
            }
        }
    }

    /**
     * A native launcher has no console: whatever the application prints
     * would be lost, the stack trace of a failed start included. Without a
     * terminal attached, both streams go to {@code logs/<install>.log} in the
     * data directory instead, rewritten on every start.
     */
    private static void redirectConsole(StarterConfig config) throws IOException {
        if (System.console() != null && System.console().isTerminal()) {
            return;
        }
        Path logs = Files.createDirectories(config.dataDirectory().resolve("logs"));
        PrintStream log = new PrintStream(new FileOutputStream(logs.resolve(config.install() + ".log").toFile()),
                true, StandardCharsets.UTF_8);
        System.setOut(log);
        System.setErr(log);
    }

    private static void log(String message) {
        System.err.println("[starter] " + Instant.now() + " " + message);
    }
}
