package de.bsommerfeld.wsbg.terminal.ui;

import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.PowerModeChangedEvent;
import de.bsommerfeld.wsbg.terminal.core.i18n.I18nService;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Observable projection of {@link GlobalConfig} for the Settings UI.
 * Every property change is immediately written back to the config
 * and persisted to disk. The PopOver only binds against these
 * properties — it never touches the config directly.
 *
 * <p>
 * Also manages stackable warning banners and a lightweight
 * GitHub update check. Ollama model availability is checked to
 * determine whether power mode changes require a restart (model
 * download) or can be hot-swapped.
 */
final class SettingsViewModel {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsViewModel.class);
    private static final String GITHUB_RELEASES_API = "https://api.github.com/repos/bsommerfeld/wsbg-terminal/releases/latest";
    private static final String OLLAMA_TAGS_URL = "http://localhost:11434/api/tags";

    /** The 12b model required by power mode (only gemma3 is upgraded). */
    private static final String POWER_MODE_MODEL = "gemma3:12b";

    // ── Agent ───────────────────────────────────────────────────────
    private final BooleanProperty powerMode = new SimpleBooleanProperty();

    // ── Headlines ───────────────────────────────────────────────────
    private final BooleanProperty headlinesEnabled = new SimpleBooleanProperty();
    private final BooleanProperty headlinesShowAll = new SimpleBooleanProperty();
    private final StringProperty headlinesTopics = new SimpleStringProperty();

    // ── User ────────────────────────────────────────────────────────
    private final StringProperty language = new SimpleStringProperty();
    private final BooleanProperty autoUpdate = new SimpleBooleanProperty();

    // ── Banners ─────────────────────────────────────────────────────
    private final ObservableList<Banner> banners = FXCollections.observableArrayList();

    // ── State ───────────────────────────────────────────────────────
    /** True when any banner is active — drives the settings icon alert state. */
    private final BooleanProperty alertActive = new SimpleBooleanProperty(false);
    private final BooleanProperty updateAvailable = new SimpleBooleanProperty(false);

    /** Callback set by PopOver to rebuild UI after language change. */
    private Runnable onLanguageChanged;

    private final GlobalConfig config;
    private final I18nService i18n;
    private final ApplicationEventBus eventBus;

    SettingsViewModel(GlobalConfig config, I18nService i18n, ApplicationEventBus eventBus) {
        this.config = config;
        this.i18n = i18n;
        this.eventBus = eventBus;

        // Seed from config
        powerMode.set(config.getAgent().isPowerMode());
        headlinesEnabled.set(config.getHeadlines().isEnabled());
        headlinesShowAll.set(config.getHeadlines().isShowAll());
        headlinesTopics.set(String.join(", ", config.getHeadlines().getTopics()));
        language.set(config.getUser().getLanguage());
        autoUpdate.set(config.getUser().isAutoUpdate());

        // Write-through listeners
        powerMode.addListener((obs, o, n) -> {
            config.getAgent().setPowerMode(n);
            persist();
            // Reinitialize AgentBrain models at runtime
            eventBus.post(new PowerModeChangedEvent());
            // Only show restart banner if power mode is ON and models are missing.
            // If turning off or models already present, hot-swap is sufficient.
            if (n) {
                checkPowerModeModels();
            }
        });

        headlinesEnabled.addListener((obs, o, n) -> {
            config.getHeadlines().setEnabled(n);
            persist();
        });

        headlinesShowAll.addListener((obs, o, n) -> {
            config.getHeadlines().setShowAll(n);
            persist();
        });

        headlinesTopics.addListener((obs, o, n) -> {
            config.getHeadlines().setTopics(
                    Arrays.stream(n.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toList()));
            persist();
        });

        language.addListener((obs, o, n) -> {
            config.getUser().setLanguage(n);
            persist();
            // Hot-swap: switch I18nService locale, notify external components, rebuild popover
            i18n.setLocale(Locale.forLanguageTag(n));
            eventBus.post(new ControlEvents.LanguageChangedEvent());
            if (onLanguageChanged != null) {
                onLanguageChanged.run();
            }
        });

        autoUpdate.addListener((obs, o, n) -> {
            config.getUser().setAutoUpdate(n);
            persist();
        });

        // Alert state tracks banner list
        banners.addListener((javafx.collections.ListChangeListener<Banner>) c ->
                alertActive.set(!banners.isEmpty()));

        checkForUpdates();
    }

    // ── Property accessors ──────────────────────────────────────────

    BooleanProperty powerModeProperty() {
        return powerMode;
    }

    BooleanProperty headlinesEnabledProperty() {
        return headlinesEnabled;
    }

    BooleanProperty headlinesShowAllProperty() {
        return headlinesShowAll;
    }

    StringProperty headlinesTopicsProperty() {
        return headlinesTopics;
    }

    StringProperty languageProperty() {
        return language;
    }
                

    BooleanProperty autoUpdateProperty() {
        return autoUpdate;
    }

    ObservableList<Banner> banners() {
        return banners;
    }

    BooleanProperty alertActiveProperty() {
        return alertActive;
    }

    BooleanProperty updateAvailableProperty() {
        return updateAvailable;
    }

    I18nService i18n() {
        return i18n;
    }

    void setOnLanguageChanged(Runnable callback) {
        this.onLanguageChanged = callback;
    }

    // ── Banner management ───────────────────────────────────────────

    void addBanner(Banner banner) {
        boolean exists = banners.stream()
                .anyMatch(b -> b.action() == banner.action());
        if (!exists) {
            banners.add(banner);
        }
    }

    void removeBanner(Banner banner) {
        banners.remove(banner);
    }

    void removeBannerByAction(Banner.Action action) {
        banners.removeIf(b -> b.action() == action);
    }

    // ── Actions ─────────────────────────────────────────────────────

    /** Restarts by launching the launcher executable and exiting. */
    void restartViaLauncher() {
        restartViaLauncher(false);
    }

    /**
     * Restarts by launching the launcher executable.
     *
     * @param forceUpdate when true, the launcher runs the full update pipeline
     *                    regardless of the auto-update setting
     */
    void restartViaLauncher(boolean forceUpdate) {
        try {
            Path appDir = StorageUtils.getAppDataDir("wsbg-terminal");
            String[] command = buildLauncherCommand(appDir, forceUpdate);
            new ProcessBuilder(command)
                    .directory(appDir.toFile())
                    .inheritIO()
                    .start();
            Platform.exit();
        } catch (IOException e) {
            LOG.error("Failed to restart via launcher", e);
        }
    }

    // ── Ollama model check ──────────────────────────────────────────

    /**
     * Checks if the 12b models required by power mode are installed.
     * If any are missing, shows a restart banner so the launcher
     * can pull them via the environment setup phase.
     */
    private void checkPowerModeModels() {
        Thread.ofVirtual().name("ollama-model-check").start(() -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .build();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(OLLAMA_TAGS_URL))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request,
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    String body = response.body();
                    if (!body.contains("\"" + POWER_MODE_MODEL + "\"")) {
                        Platform.runLater(() -> addBanner(new Banner(
                                i18n.get("settings.banner.restart"),
                                Banner.Action.RESTART)));
                    }
                    // Model present -> hot-swap, no banner needed
                } else {
                    // Ollama not reachable — need restart to set up environment
                    Platform.runLater(() -> addBanner(new Banner(
                            i18n.get("settings.banner.restart"),
                            Banner.Action.RESTART)));
                }
            } catch (Exception e) {
                // Ollama not running — need restart
                LOG.debug("Ollama check failed, assuming models unavailable", e);
                Platform.runLater(() -> addBanner(new Banner(
                        i18n.get("settings.banner.restart"),
                        Banner.Action.RESTART)));
            }
        });
    }

    // ── Update check ────────────────────────────────────────────────

    /**
     * Lightweight background check against GitHub releases API.
     * Compares the latest tag against the local version.txt.
     */
    private void checkForUpdates() {
        Thread.ofVirtual().name("update-check").start(() -> {
            try {
                Path versionFile = StorageUtils.getAppDataDir("wsbg-terminal")
                        .resolve("version.txt");
                if (!Files.exists(versionFile))
                    return;

                String localVersion = Files.readString(versionFile).strip();
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(GITHUB_RELEASES_API))
                        .timeout(Duration.ofSeconds(10))
                        .header("Accept", "application/vnd.github.v3+json")
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request,
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    String body = response.body();
                    String remoteTag = extractTagName(body);
                    if (remoteTag != null && !remoteTag.equals(localVersion)) {
                        Platform.runLater(() -> {
                            updateAvailable.set(true);
                            addBanner(new Banner(
                                    i18n.get("settings.banner.update_available"),
                                    Banner.Action.UPDATE));
                        });
                    }
                }
            } catch (Exception e) {
                LOG.debug("Update check failed (non-critical)", e);
            }
        });
    }

    /** Minimal JSON extraction — avoids pulling in a JSON library. */
    private static String extractTagName(String json) {
        String key = "\"tag_name\"";
        int idx = json.indexOf(key);
        if (idx < 0)
            return null;
        int valueStart = json.indexOf('"', json.indexOf(':', idx) + 1);
        int valueEnd = json.indexOf('"', valueStart + 1);
        if (valueStart < 0 || valueEnd < 0)
            return null;
        return json.substring(valueStart + 1, valueEnd);
    }

    // ── Launcher resolution ─────────────────────────────────────────

    /**
     * Builds the OS-appropriate command to run the native launcher.
     * JPackage installs the launcher under predictable paths per platform.
     */
    private static String[] buildLauncherCommand(Path appDir, boolean forceUpdate) {
        String os = System.getProperty("os.name", "").toLowerCase();

        // When forceUpdate is true, omit --skip-update so the launcher
        // runs the full update + environment pipeline
        String skipFlag = forceUpdate ? null : "--skip-update";

        if (os.contains("mac")) {
            Path macLauncher = Path.of("/Applications/WSBG Terminal.app/Contents/MacOS/WSBG Terminal");
            if (Files.isExecutable(macLauncher)) {
                return skipFlag != null
                        ? new String[] { macLauncher.toString(), skipFlag }
                        : new String[] { macLauncher.toString() };
            }
        }

        if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null) {
                Path winLauncher = Path.of(localAppData, "WSBG Terminal", "WSBG Terminal.exe");
                if (Files.exists(winLauncher)) {
                    return skipFlag != null
                            ? new String[] { winLauncher.toString(), skipFlag }
                            : new String[] { winLauncher.toString() };
                }
            }
        }

        Path linuxLauncher = Path.of("/opt/wsbg-terminal/bin/wsbg-terminal");
        if (Files.isExecutable(linuxLauncher)) {
            return skipFlag != null
                    ? new String[] { linuxLauncher.toString(), skipFlag }
                    : new String[] { linuxLauncher.toString() };
        }

        LOG.warn("Native launcher not found — falling back to java -jar");
        return skipFlag != null
                ? new String[] { "java", "-jar", appDir.resolve("lib/launcher.jar").toString(), skipFlag }
                : new String[] { "java", "-jar", appDir.resolve("lib/launcher.jar").toString() };
    }

    // ── Persistence ─────────────────────────────────────────────────

    private void persist() {
        try {
            config.save();
        } catch (Exception ex) {
            LOG.error("Failed to persist settings", ex);
        }
    }

    // ── Banner record ───────────────────────────────────────────────

    record Banner(String message, Action action) {
        enum Action {
            RESTART, UPDATE
        }
    }
}
