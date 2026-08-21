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
            Map.entry("Closing running terminal", "Laufendes Terminal wird beendet"),
            Map.entry("Launching application", "Anwendung starten"),
            Map.entry("Setup completed with warnings", "Setup mit Warnungen abgeschlossen"),
            Map.entry("Running environment setup", "Umgebung einrichten"),
            Map.entry("Setting up environment", "Umgebung einrichten"),
            Map.entry("Setup complete", "Setup abgeschlossen"),
            Map.entry("Setup warning", "Setup-Warnung"),
            Map.entry("Setup timed out", "Zeitüberschreitung beim Setup"),
            Map.entry("No setup script found", "Kein Setup-Skript gefunden"),
            Map.entry("Installing AI platform", "KI-Plattform installieren"),
            Map.entry("Updating AI platform", "KI-Plattform updaten"),
            Map.entry("Installing AI models", "KI-Modelle installieren"),
            Map.entry("Installing browser runtime", "Browser-Runtime installieren"),
            Map.entry("Installing OCR runtime", "Texterkennung installieren"),
            Map.entry("Installing fonts", "Schriftarten installieren"),
            Map.entry("Cleaning up old models", "Räume Altlasten weg"),
            Map.entry("Using external AI endpoint", "Externer KI-Endpunkt"),
            Map.entry("Choose your language", "Sprache wählen"),
            Map.entry("You can change this later in the settings", "Später in den Einstellungen änderbar"),
            Map.entry("OK", "Ok"),
            Map.entry("Error", "Fehler"),
            Map.entry("Launcher failed", "Start fehlgeschlagen"),
            Map.entry("Cannot create app directory", "Anwendungsverzeichnis kann nicht erstellt werden")
    );

    /** The languages the launcher and the terminal UI both speak. */
    static final String[] LANGUAGES = {"de", "en"};

    /**
     * Volatile: the language-choice screen switches it mid-run, and the
     * pipeline reads it from the update thread while the EDT paints.
     */
    private volatile String language;

    /** Whether {@link #language} came from config.toml rather than a fallback. */
    private final boolean explicit;

    LauncherI18n(Path appDir) {
        String configured = configuredLanguage(appDir);
        explicit = !configured.isEmpty();
        language = explicit ? configured : systemLanguage();
    }

    String get(String key) {
        return translate(key, language);
    }

    /**
     * Translation into an explicitly named language — for the language-choice
     * screen, which has to label every option in its OWN language, not in the
     * one currently active.
     */
    static String translate(String key, String language) {
        if ("de".equals(language)) {
            return DE.getOrDefault(key, key);
        }
        return key;
    }

    String language() {
        return language;
    }

    /**
     * Whether the user's language is on record in config.toml. False means
     * nothing was ever chosen (fresh install, or the launcher was closed
     * before the choice) — the launcher then asks.
     */
    boolean explicit() {
        return explicit;
    }

    /** Applies the language picked in the launcher's language-choice screen. */
    void switchTo(String language) {
        this.language = language;
    }

    /**
     * Reads {@code language} from config.toml, or empty when the file or the
     * key is missing.
     */
    private static String configuredLanguage(Path appDir) {
        Path configFile = appDir.resolve("config.toml");
        if (!Files.exists(configFile)) return "";
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
        return "";
    }

    /** Fallback while no choice is on record: the system locale, else English. */
    private static String systemLanguage() {
        return "de".equals(System.getProperty("user.language", "en")) ? "de" : "en";
    }
}
