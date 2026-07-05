package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import de.bsommerfeld.wsbg.terminal.ui.CefHost;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Settings view's backend: the few user preferences that live in the
 * persisted {@link GlobalConfig} (not the client-only appearance toggles, which
 * the page keeps in localStorage). Each {@code set} mutates the config in memory,
 * persists it to {@code config.toml} ({@link GlobalConfig#save()}), and echoes the
 * full settings snapshot back so every connected client stays in sync.
 *
 * <p>Inbound: {@code {type:"settings", payload:{command:"get"|"set", key?, value?}}}.
 * Keys (all optional on the wire, ignored if unknown):
 * <ul>
 *   <li>{@code analyzeImages} — boolean (default true) → {@code headlines.analyze-images}
 *       (off = skip all vision for fast text-only headlines);</li>
 *   <li>{@code language} — {@code "de"}/{@code "en"} → {@code user.language};</li>
 *   <li>{@code autoUpdate} — boolean → {@code user.auto-update}.</li>
 * </ul>
 * Also handles {@code {command:"clear-data"}} (delegated to {@link DataWipeService}:
 * a full terminal wipe) and {@code {command:"open-logs"}} (reveals the app-data
 * folder, which holds {@code logs/}, in the OS file manager).
 *
 * <p>Outbound (after every {@code set}, on {@code get}, and on client open): one
 * {@code settings} broadcast carrying the current value of every key.
 */
@Singleton
public final class SettingsBridge {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsBridge.class);

    private final GlobalConfig config;
    private final DataWipeService dataWipe;
    private final PushHub hub;

    @Inject
    public SettingsBridge(GlobalConfig config, DataWipeService dataWipe, PushHub hub) {
        this.config = config;
        this.dataWipe = dataWipe;
        this.hub = hub;
        hub.on("settings", this::onCommand);
        hub.onClientOpen(this::push);
    }

    private void onCommand(Map<String, Object> payload) {
        try {
            Object cmd = payload.get("command");
            if ("set".equals(cmd)) {
                String key = payload.get("key") instanceof String s ? s : null;
                Object value = payload.get("value");
                if (key != null && apply(config, key, value)) {
                    config.save();
                }
            } else if ("clear-data".equals(cmd)) {
                dataWipe.clearData();
            } else if ("open-logs".equals(cmd)) {
                CefHost.openFolder(StorageUtils.getAppDataDir());
            }
            // "get" (and any "set") answers with the full snapshot.
            push();
        } catch (Exception e) {
            LOG.warn("settings command failed: {}", e.getMessage());
        }
    }

    /** Applies one key=value to the config. Returns whether anything changed. Package-private for testing. */
    static boolean apply(GlobalConfig config, String key, Object value) {
        switch (key) {
            case "language" -> {
                if (value instanceof String s && (s.equals("de") || s.equals("en"))) {
                    config.getUser().setLanguage(s);
                    return true;
                }
                return false;
            }
            case "autoUpdate" -> {
                config.getUser().setAutoUpdate(Payloads.asBool(value));
                return true;
            }
            case "analyzeImages" -> {
                config.getHeadlines().setAnalyzeImages(Payloads.asBool(value));
                return true;
            }
            default -> {
                LOG.debug("settings: ignoring unknown key '{}'", key);
                return false;
            }
        }
    }

    private void push() {
        hub.broadcast("settings", snapshot(config));
    }

    /** The full settings payload the page reads. Package-private for testing. */
    static Map<String, Object> snapshot(GlobalConfig config) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("analyzeImages", config.getHeadlines().isAnalyzeImages());
        out.put("language", config.getUser().getLanguage());
        out.put("autoUpdate", config.getUser().isAutoUpdate());
        return out;
    }
}
