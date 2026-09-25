package de.bsommerfeld.starter;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class StarterConfigTest {

    private static Properties properties(String... pairs) {
        Properties properties = new Properties();
        for (int i = 0; i < pairs.length; i += 2) {
            properties.setProperty(pairs[i], pairs[i + 1]);
        }
        return properties;
    }

    @Test
    void readsEverything() {
        StarterConfig config = StarterConfig.from(properties(
                "starter.data", "WSBG",
                "starter.install", "app",
                "starter.main", "de.x.app/de.x.app.Main",
                "starter.native-access", "javafx.graphics, de.x.orb",
                "starter.seed", "/bundle/seed",
                "starter.seed.installs", "app,updater"));

        assertEquals("app", config.install());
        assertEquals(config.dataDirectory().resolve("app"), config.installDirectory());
        assertEquals("de.x.app", config.mainModule());
        assertEquals("de.x.app.Main", config.mainClass());
        assertEquals(List.of("javafx.graphics", "de.x.orb"), config.nativeAccess());
        assertEquals(Path.of("/bundle/seed"), config.seed());
        assertEquals(List.of("app", "updater"), config.seedInstalls());
    }

    @Test
    void optionalsDefault_seedInstallsToTheBootedOne() {
        StarterConfig config = StarterConfig.from(properties(
                "starter.data", "WSBG", "starter.install", "updater", "starter.main", "m/C"));

        assertNull(config.seed());
        assertEquals(List.of(), config.nativeAccess());
        assertEquals(List.of("updater"), config.seedInstalls());
    }

    @Test
    void rejectsMissingOrMalformed() {
        assertThrows(IllegalArgumentException.class, () -> StarterConfig.from(properties(
                "starter.install", "app", "starter.main", "m/C")));
        assertThrows(IllegalArgumentException.class, () -> StarterConfig.from(properties(
                "starter.data", "WSBG", "starter.install", "app", "starter.main", "NoModule")));
    }
}
