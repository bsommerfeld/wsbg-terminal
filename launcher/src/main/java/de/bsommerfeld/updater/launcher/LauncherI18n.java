package de.bsommerfeld.updater.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Minimal i18n for launcher status strings. Reads the language
 * from {@code config.toml} and maps English keys to localized
 * display strings. Falls back to the system locale, then to English.
 *
 * <p>Supported languages: {@code en}, {@code de}.
 */
final class LauncherI18n {

    private static final Map<String, String> DE = Map.ofEntries(
            Map.entry("Checking for updates", "Suche nach Updates"),
            Map.entry("Up to date", "Aktuell"),
            Map.entry("Update complete", "Update abgeschlossen"),
            Map.entry("Downloading update", "Update herunterladen"),
            Map.entry("Downloading dependencies", "Abhängigkeiten herunterladen"),
            Map.entry("Extracting files", "Dateien entpacken"),
            Map.entry("Extracting dependencies", "Abhängigkeiten entpacken"),
            Map.entry("Verifying integrity", "Integrität prüfen"),
            Map.entry("Cleaning up", "Aufräumen"),
            Map.entry("Update check failed", "Update-Prüfung fehlgeschlagen"),
            Map.entry("Launching application", "Anwendung starten"),
            Map.entry("Setup completed with warnings", "Setup mit Warnungen abgeschlossen"),
            Map.entry("Starting without updates", "Starte ohne Updates"),
            Map.entry("Setting up environment", "Umgebung einrichten"),
            Map.entry("Installing AI platform", "KI-Plattform installieren"),
            Map.entry("Installing AI models", "KI-Modelle installieren")
    );

    private final String language;

    LauncherI18n(Path appDir) {
        language = resolveLanguage(appDir);
    }

    String get(String key) {
        if ("de".equals(language)) {
            return DE.getOrDefault(key, key);
        }
        return key;
    }

    String language() {
        return language;
    }

    /**
     * Reads the language from config.toml → falls back to system
     * locale → falls back to "en".
     */
    private static String resolveLanguage(Path appDir) {
        Path configFile = appDir.resolve("config.toml");
        if (Files.exists(configFile)) {
            try {
                for (String line : Files.readAllLines(configFile)) {
                    String trimmed = line.strip();
                    if (trimmed.startsWith("language")) {
                        int eq = trimmed.indexOf('=');
                        if (eq > 0) {
                            String value = trimmed.substring(eq + 1).strip()
                                    .replace("\"", "").replace("'", "");
                            if (!value.isEmpty()) return value;
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }

        // Fall back to system locale
        String systemLang = System.getProperty("user.language", "en");
        if ("de".equals(systemLang)) return "de";
        return "en";
    }
}
