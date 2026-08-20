package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.updater.catalog.ModelCatalog;
import de.bsommerfeld.updater.endpoint.EndpointProbe;
import de.bsommerfeld.wsbg.terminal.agent.AgentBrain;
import de.bsommerfeld.wsbg.terminal.agent.OllamaServerManager;
import de.bsommerfeld.wsbg.terminal.core.config.AgentConfig;
import de.bsommerfeld.wsbg.terminal.core.config.AiEndpoint;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import de.bsommerfeld.wsbg.terminal.ui.CefHost;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 *   <li>{@code language} — {@code "de"}/{@code "en"} → {@code user.language};</li>
 *   <li>{@code autoUpdate} — boolean → {@code user.auto-update};</li>
 *   <li>{@code experimentalUpdates} — boolean → {@code user.experimental-updates}
 *       (stored as {@code "yes"}/{@code "no"}; the launcher's unanswered third
 *       state can only be produced by never having asked).</li>
 *   <li>the {@code ai*} keys — the AI endpoint (advanced): mode, address,
 *       model, auth header value, context window, parallel slots, plus the
 *       managed runtime's model tag. Changing any endpoint key posts an
 *       {@link ControlEvents.AiEndpointChangedEvent} so the running agent
 *       rebuilds its handles; {@code aiModelTag} is the exception, because a
 *       different LOCAL model has to be downloaded by the launcher first and
 *       therefore only takes effect on the next start.</li>
 * </ul>
 * Also handles {@code {command:"clear-data"}} (delegated to {@link DataWipeService}:
 * a full terminal wipe), {@code {command:"open-logs"}} (reveals the app-data
 * folder, which holds {@code logs/}, in the OS file manager) and
 * {@code {command:"test-endpoint"}} (probes an address the user is still typing —
 * see {@link EndpointProbe} for why that button exists).
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
    private final ApplicationEventBus eventBus;
    /** Only for "which model is ACTUALLY running" - see {@link #snapshot}. */
    private final AgentBrain brain;

    @Inject
    public SettingsBridge(GlobalConfig config, DataWipeService dataWipe, PushHub hub,
            ApplicationEventBus eventBus, AgentBrain brain) {
        this.config = config;
        this.dataWipe = dataWipe;
        this.hub = hub;
        this.eventBus = eventBus;
        this.brain = brain;
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
                    // An endpoint key moved: the running agent is still holding
                    // handles for the old one. aiModelTag is deliberately NOT in
                    // this set - that model may not be on the machine yet, and
                    // only the launcher can fetch it.
                    if (ENDPOINT_KEYS.contains(key)) {
                        eventBus.post(new ControlEvents.AiEndpointChangedEvent());
                    }
                }
            } else if ("test-endpoint".equals(cmd)) {
                testEndpoint(payload);
                return;   // answers on its own topic; the snapshot is unchanged
            } else if ("clear-data".equals(cmd)) {
                dataWipe.clearData();
            } else if ("restart".equals(cmd)) {
                // The model the user picked is not on the machine; only the
                // launcher's setup step can fetch it, and only on a start.
                LOG.info("User asked to restart for a model change.");
                de.bsommerfeld.wsbg.terminal.ui.AppMain.relaunchForModelChange();
                return;
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
            case "experimentalUpdates" -> {
                // Persisted as a tri-state string, because "never asked" is a
                // third state the launcher's first-start question depends on.
                // A toggle can only ever produce the two answers.
                config.getUser().setExperimentalUpdates(Payloads.asBool(value) ? "yes" : "no");
                return true;
            }
            case "aiEndpointMode" -> {
                // Anything but the literal "remote" is the managed runtime -
                // the same rule AiEndpoint.resolve applies, so a garbage value
                // can never leave the app pointing nowhere.
                boolean remote = "remote".equals(Payloads.str(value));
                config.getAgent().setEndpointMode(remote ? "remote" : "managed");
                return true;
            }
            case "aiEndpointUrl" -> {
                // Normalized on the way IN, so the address the panel echoes
                // back is the one the endpoint will actually call.
                config.getAgent().setEndpointUrl(AiEndpoint.normalizeUrl(str(value)));
                return true;
            }
            case "aiEndpointApi" -> {
                // Anything but "openai" means the native Ollama API - the
                // protocol the pipeline loses nothing on, so it is the safe end
                // of a garbage value.
                boolean openAi = "openai".equals(Payloads.str(value));
                config.getAgent().setEndpointApi(openAi ? "openai" : "ollama");
                return true;
            }
            case "aiEndpointModel" -> {
                config.getAgent().setEndpointModel(str(value).strip());
                return true;
            }
            case "aiEndpointAuth" -> {
                config.getAgent().setEndpointAuth(str(value).strip());
                return true;
            }
            case "aiEndpointContext" -> {
                // 0 = "use the default"; a negative value is a typo, not an
                // instruction.
                config.getAgent().setEndpointContextTokens(Math.max(0, Payloads.intOr(value, 0)));
                return true;
            }
            case "aiEndpointSlots" -> {
                config.getAgent().setEndpointParallelism(Math.max(0, Payloads.intOr(value, 0)));
                return true;
            }
            case "aiModelTag" -> {
                // The MANAGED runtime's model. Family-gated exactly like the
                // launcher's own read of this key - an unknown tag would reach
                // 'ollama pull' verbatim on the next start.
                String tag = str(value).strip().toLowerCase(java.util.Locale.ROOT);
                if (!tag.isEmpty() && !ModelCatalog.isDeployedFamily(tag)) return false;
                config.getAgent().setModelTag(tag);
                return true;
            }
            default -> {
                LOG.debug("settings: ignoring unknown key '{}'", key);
                return false;
            }
        }
    }

    private void push() {
        Map<String, Object> out = snapshot(config);
        // The model the pipeline is running RIGHT NOW, which is not always the
        // one that is configured: a tag that is not in the store yet resolves
        // to an installed sibling at startup. Without this the panel names a
        // model nothing is using and the user sees "no change".
        out.put("aiModelActive", brain.getAgentModelName());
        hub.broadcast("settings", out);
    }

    /** The endpoint keys whose change the running agent has to react to. */
    private static final java.util.Set<String> ENDPOINT_KEYS = java.util.Set.of(
            "aiEndpointMode", "aiEndpointUrl", "aiEndpointApi", "aiEndpointModel",
            "aiEndpointAuth", "aiEndpointContext", "aiEndpointSlots");

    /**
     * Probes an address the user is still typing and answers on its own topic.
     * Off the socket thread: an unreachable host costs the full connect
     * timeout, and freezing the panel for it would look exactly like the
     * failure the button exists to diagnose.
     */
    private void testEndpoint(Map<String, Object> payload) {
        String url = payload.get("url") instanceof String s ? s : "";
        String auth = payload.get("auth") instanceof String s ? s : "";
        String header = config.getAgent().getEndpointAuthHeader();
        Thread.ofVirtual().name("endpoint-probe").start(() -> {
            EndpointProbe.Result result = EndpointProbe.probe(url, header, auth);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", result.ok());
            // The detected protocol travels with the verdict so the panel can
            // set the field itself - the user knows their server's address,
            // not whether it serves Ollama's chat API or OpenAI's.
            out.put("api", result.api().name().toLowerCase(java.util.Locale.ROOT));
            // The server's / network's own words, unlocalized on purpose: it is
            // quoted evidence, and our translation of someone else's error
            // would only obscure it.
            out.put("reason", result.reason());
            out.put("models", result.models());
            try {
                hub.broadcast("ai-endpoint-test", out);
            } catch (Exception e) {
                LOG.debug("ai-endpoint-test broadcast failed: {}", e.getMessage());
            }
        });
    }

    private static String str(Object value) {
        String s = Payloads.str(value);
        return s == null ? "" : s;
    }

    /** The full settings payload the page reads. Package-private for testing. */
    static Map<String, Object> snapshot(GlobalConfig config) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("language", config.getUser().getLanguage());
        out.put("autoUpdate", config.getUser().isAutoUpdate());
        out.put("experimentalUpdates", config.getUser().isExperimentalUpdates());

        AgentConfig agent = config.getAgent();
        // The RESOLVED mode, not the raw key: a half-filled remote setup runs
        // as managed, and the panel must show what is actually in force.
        out.put("aiEndpointMode", AiEndpoint.resolve(agent).managed() ? "managed" : "remote");
        out.put("aiEndpointUrl", agent.getEndpointUrl());
        out.put("aiEndpointApi", AiEndpoint.resolve(agent).openAi() ? "openai" : "ollama");
        out.put("aiEndpointModel", agent.getEndpointModel());
        out.put("aiEndpointAuth", agent.getEndpointAuth());
        out.put("aiEndpointContext", agent.getEndpointContextTokens());
        out.put("aiEndpointSlots", agent.getEndpointParallelism());
        // The managed runtime's model: the stored choice (empty = the managed
        // default), the tag that default resolves to, and the tiers to choose
        // from - built from the SAME catalog the launcher installs out of, so
        // the panel can never offer something the installer would not pull.
        out.put("aiModelTag", agent.getModelTag());
        out.put("aiModelDefault", ModelCatalog.DEFAULT.tagFor(AgentConfig.isAppleSilicon()));
        out.put("aiModelTiers", modelTiers());
        // Which of those tiers are actually on this machine. The list offers
        // everything the installer CAN fetch; without this the panel cannot say
        // which choice is instant and which needs the next start.
        out.put("aiModelInstalled", new ArrayList<>(OllamaServerManager.installedModelTags()));
        // Whether a restart can achieve anything. The terminal downloads no
        // model itself - it quits and starts the LAUNCHER, whose setup step
        // fetches whatever the config now names. With no launcher (a dev
        // run.sh start) that exit would close the app and start nothing, so the
        // button must not be offered. Same gate UpdateService puts on its own
        // relaunch button.
        String launcher = System.getenv("WSBG_LAUNCHER_EXECUTABLE");
        out.put("canRestart", launcher != null && !launcher.isBlank());
        return out;
    }

    /**
     * The tiers, split into the pieces the panel renders separately: the family
     * name, the parameter size and the disk figure. They used to travel as one
     * string ("Gemma 4 · 26b - 18 GB"), which the closed dropdown then cut in
     * half - the numbers are chips now, and a chip cannot be truncated away
     * without the whole row shrinking first.
     */
    private static List<Map<String, Object>> modelTiers() {
        boolean mlx = AgentConfig.isAppleSilicon();
        List<Map<String, Object>> tiers = new ArrayList<>();
        for (ModelCatalog tier : ModelCatalog.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tag", tier.tagFor(mlx));
            m.put("name", familyName(tier));
            m.put("size", sizeLabel(tier));
            m.put("diskGb", tier.diskGbFor(mlx));
            tiers.add(m);
        }
        return tiers;
    }

    /** "Granite 4.1 · 3b" → "Granite 4.1"; a name without a size part is kept whole. */
    private static String familyName(ModelCatalog tier) {
        String name = tier.displayName();
        int sep = name.lastIndexOf(" · ");
        return sep > 0 ? name.substring(0, sep) : name;
    }

    /** The parameter size straight off the tag - always there, unlike in the name. */
    private static String sizeLabel(ModelCatalog tier) {
        String tag = tier.baseTag();
        int colon = tag.indexOf(':');
        return colon > 0 ? tag.substring(colon + 1) : "";
    }
}
