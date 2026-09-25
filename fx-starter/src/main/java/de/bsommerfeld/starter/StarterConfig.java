package de.bsommerfeld.starter;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * What one native launcher boots, read from system properties - the form a
 * launcher's configuration hands them over in (jpackage: {@code --java-options},
 * where {@code $APPDIR} expands to the bundle's application directory):
 *
 * <pre>
 * starter.data           the application's name, which names its data directory ({@link DataDirectory})
 * starter.install        the install to boot, a directory inside the data directory
 * starter.main           the main class as module/class
 * starter.native-access  modules of the install granted native access, comma-separated (optional)
 * starter.seed           the bundled seeds: one directory or zip per install, named as it (optional)
 * starter.seed.installs  the installs this launcher seeds, comma-separated (optional, default: starter.install)
 * </pre>
 *
 * @param dataDirectory the application's data directory
 * @param install       the name of the install to boot
 * @param mainModule    the module holding the main class
 * @param mainClass     the fully qualified main class
 * @param nativeAccess  the modules that get native access
 * @param seed          the directory of the bundled seeds, or {@code null} for none
 * @param seedInstalls  the installs laid out from the seed
 */
public record StarterConfig(Path dataDirectory, String install, String mainModule, String mainClass,
        List<String> nativeAccess, Path seed, List<String> seedInstalls) {

    static final String DATA = "starter.data";
    static final String INSTALL = "starter.install";
    static final String MAIN = "starter.main";
    static final String NATIVE_ACCESS = "starter.native-access";
    static final String SEED = "starter.seed";
    static final String SEED_INSTALLS = "starter.seed.installs";

    public StarterConfig {
        nativeAccess = List.copyOf(nativeAccess);
        seedInstalls = List.copyOf(seedInstalls);
    }

    /** The directory of the install to boot. */
    public Path installDirectory() {
        return dataDirectory.resolve(install);
    }

    /**
     * Reads the configuration.
     *
     * @throws IllegalArgumentException when a required property is missing or malformed
     */
    public static StarterConfig from(Properties properties) {
        String install = require(properties, INSTALL);
        String main = require(properties, MAIN);
        int slash = main.indexOf('/');
        if (slash <= 0 || slash == main.length() - 1) {
            throw new IllegalArgumentException(MAIN + " must be module/class, got: " + main);
        }

        String seed = properties.getProperty(SEED);
        List<String> seedInstalls = list(properties.getProperty(SEED_INSTALLS));
        return new StarterConfig(
                DataDirectory.of(require(properties, DATA)),
                install,
                main.substring(0, slash),
                main.substring(slash + 1),
                list(properties.getProperty(NATIVE_ACCESS)),
                seed == null || seed.isBlank() ? null : Path.of(seed),
                seedInstalls.isEmpty() ? List.of(install) : seedInstalls);
    }

    private static String require(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing system property " + key);
        }
        return value.strip();
    }

    private static List<String> list(String value) {
        if (value == null) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
