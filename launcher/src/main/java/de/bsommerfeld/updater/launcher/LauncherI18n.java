package de.bsommerfeld.updater.launcher;

import de.bsommerfeld.updater.api.UpdatePhase;

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

    // The update-phase keys are sourced from the shared UpdatePhase enum, the
    // single source of truth for the phase-token contract with the updater
    // module (UpdateProgress.phase()). The remaining keys are launcher-local
    // status strings and setup-script phase tokens.
    private static final Map<String, String> DE = Map.ofEntries(
            Map.entry(UpdatePhase.CHECKING.token(), "Suche nach Updates"),
            Map.entry(UpdatePhase.UP_TO_DATE.token(), "Aktuell"),
            Map.entry(UpdatePhase.UPDATE_COMPLETE.token(), "Update abgeschlossen"),
            Map.entry(UpdatePhase.DOWNLOADING_UPDATE.token(), "Update herunterladen"),
            Map.entry(UpdatePhase.DOWNLOADING_DEPENDENCIES.token(), "Abhängigkeiten herunterladen"),
            Map.entry(UpdatePhase.EXTRACTING_FILES.token(), "Dateien entpacken"),
            Map.entry(UpdatePhase.EXTRACTING_DEPENDENCIES.token(), "Abhängigkeiten entpacken"),
            Map.entry(UpdatePhase.VERIFYING_INTEGRITY.token(), "Integrität prüfen"),
            Map.entry("Cleaning up", "Aufräumen"),
            Map.entry("Update check failed", "Update-Prüfung fehlgeschlagen"),
            Map.entry("Launching application", "Anwendung starten"),
            Map.entry("Setup completed with warnings", "Setup mit Warnungen abgeschlossen"),
            Map.entry("Running environment setup", "Umgebung einrichten"),
            Map.entry("Setting up environment", "Umgebung einrichten"),
            Map.entry("Setup complete", "Setup abgeschlossen"),
            Map.entry("Setup warning", "Setup-Warnung"),
            Map.entry("Setup timed out", "Zeitüberschreitung beim Setup"),
            Map.entry("No setup script found", "Kein Setup-Skript gefunden"),
            Map.entry("Installing AI platform", "KI-Plattform installieren"),
            Map.entry("Installing AI models", "KI-Modelle installieren"),
            Map.entry("Installing browser runtime", "Browser-Runtime installieren"),
            Map.entry("Installing OCR runtime", "Texterkennung installieren"),
            Map.entry("Installing fonts", "Schriftarten installieren"),
            Map.entry("Cleaning up old models", "Räume Altlasten weg"),
            Map.entry("Choose your AI model", "Wähle dein KI-Modell"),
            Map.entry("Quality", "Qualität"),
            Map.entry("Speed", "Tempo"),
            Map.entry("Recommended", "Empfohlen"),
            Map.entry("Good fit", "Passt gut"),
            Map.entry("Tight fit", "Passt knapp"),
            Map.entry("Too large", "Zu groß"),
            Map.entry("The recommendation fits your machine", "Die Empfehlung passt zu deinem Rechner"),
            Map.entry("OK", "Ok"),
            Map.entry("Error", "Fehler"),
            Map.entry("Launcher failed", "Start fehlgeschlagen"),
            Map.entry("Cannot create app directory", "Anwendungsverzeichnis kann nicht erstellt werden")
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
