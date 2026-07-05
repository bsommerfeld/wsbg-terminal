package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.ui.AppMain;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * The Settings view's "Deinstallieren": removes the whole install — launcher,
 * app data (config, Ollama store, JCEF cache, headline archive), shortcuts —
 * in one click, on every OS. The OS-specific script is built by
 * {@link UninstallScriptBuilder}; this class owns only the inbound command and
 * the {@link AppMain#uninstallAndExit} handoff.
 *
 * <p>Inbound: {@code {type:"uninstall", payload:{command:"apply"}}}. The page
 * arms the button with a second-click confirm, so arriving here IS the
 * confirmation.
 *
 * <p>The actual removal always runs DETACHED, after this process has fully
 * exited — while we live, our own shutdown (session snapshots) and the CEF
 * teardown (cache flush) would re-create files inside the data directory and
 * leave residue behind. So the flow is: stop services → spawn the detached
 * cleanup (which sleeps first) → tear CEF down → exit → cleanup runs against
 * a dead install ({@link AppMain#uninstallAndExit}).
 */
@Singleton
public final class UninstallService {

    private static final Logger LOG = LoggerFactory.getLogger(UninstallService.class);

    private final UninstallScriptBuilder scriptBuilder;

    @Inject
    public UninstallService(GlobalConfig config, PushHub hub) {
        this.scriptBuilder = new UninstallScriptBuilder(config);
        hub.on("uninstall", this::onCommand);
    }

    private void onCommand(Map<String, Object> payload) {
        if (!"apply".equals(payload.get("command"))) return;
        try {
            List<String> detached = scriptBuilder.buildDetachedCleanup();
            LOG.info("User requested uninstall — shutting down and handing off to: {}", detached);
            AppMain.uninstallAndExit(detached);
        } catch (Exception e) {
            LOG.error("Uninstall failed to start: {}", e.getMessage());
        }
    }
}
