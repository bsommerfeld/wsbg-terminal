package de.bsommerfeld.updater.launcher;

import javax.swing.SwingUtilities;
import java.util.function.BiConsumer;

/**
 * Translates the setup script's {@code (phase, detail)} events into
 * {@link LauncherWindow} calls: the numbered status label, the progress bar,
 * the download-speed readout, and the model pips. Owns the small cross-event
 * state (periodic-log throttle, speed anchor, work-group tracking) that was
 * previously five {@code long[]}/{@code String[]} accumulators inside
 * {@code LauncherMain.runEnvironmentPhase}.
 *
 * <p>The Ollama install, the model pulls, and the browser (JCEF) runtime are
 * the final three numbered steps after the update download steps; fonts/config
 * run after as an unnumbered tail.
 */
final class SetupProgressAdapter implements BiConsumer<String, String> {

    /**
     * How long the download-byte figure may sit unchanged before the speed
     * readout hides as genuinely stalled. Generous, because the figure's coarse
     * units (100 MB steps in the GB range) make long flat stretches normal
     * during a healthy download.
     */
    private static final long SPEED_STALL_HIDE_MS = 30_000;

    private final LauncherWindow window;
    private final LauncherI18n i18n;
    private final SessionLog log;

    private final int platformStep;
    private final int modelStep;
    private final int browserStep;
    private final int totalSteps;

    // Periodic logging — ollama emits many lines per second.
    private long lastLogTime;
    // Speed anchor from ollama's size output.
    private long speedAnchorBytes;
    private long speedAnchorTime;
    // Last logged message, so a transition logs immediately.
    private String lastLoggedPhase = "";
    // Last step-group seen, so a transition between the platform/model/browser
    // steps snaps the bar back to the dot and drops any stale speed.
    private String lastGroup = "";

    /**
     * @param downloadStepCount number of update-download steps already shown;
     *                          the platform step is numbered right after them
     */
    SetupProgressAdapter(LauncherWindow window, LauncherI18n i18n, SessionLog log, int downloadStepCount) {
        this.window = window;
        this.i18n = i18n;
        this.log = log;
        this.platformStep = downloadStepCount + 1;
        this.modelStep = platformStep + 1;
        this.browserStep = modelStep + 1;
        this.totalSteps = browserStep;
    }

    @Override
    public void accept(String phase, String detail) {
        long now = System.currentTimeMillis();

        // "ModelCount" is a control message, not a user-visible phase: detail
        // is "total/started" and drives the model pips (one dot per model, lit
        // from the moment its pull begins), so the user sees how many models
        // install, not just the current one. Handled first and never surfaced
        // as a status line.
        if (phase.equals("ModelCount")) {
            int[] tc = SetupDetailParser.parseModelCount(detail);
            if (tc != null) window.setModelPips(tc[0], tc[1]);
            return;
        }

        // Log transitions immediately, everything else periodically
        String logKey = phase + (detail != null && detail.contains("%") ? "" : detail);
        boolean isTransition = !logKey.equals(lastLoggedPhase);
        if (isTransition || now - lastLogTime >= 2000) {
            log.log("[setup] " + phase + (detail != null ? " — " + detail : ""));
            lastLogTime = now;
            lastLoggedPhase = logKey;
        }

        boolean isWork = phase.startsWith("Pulling")
                || phase.equals("Installing AI platform")
                || phase.equals("Installing browser runtime");
        if (!window.isVisible() && isWork) {
            SwingUtilities.invokeLater(() -> window.setVisible(true));
        }

        // Snap to the dot and drop the stale speed whenever the work group
        // changes (platform → models → browser), so the next step's bar and
        // readouts start clean instead of inheriting the previous fill.
        String group = phase.startsWith("Pulling") ? "models" : phase;
        if (!group.equals(lastGroup)) {
            // Leaving the model pulls (→ browser/fonts): drop the pips so
            // they don't linger under a later phase's bar.
            if (lastGroup.equals("models")) {
                window.clearModelPips();
            }
            lastGroup = group;
            window.resetProgress();
            speedAnchorBytes = 0;
            speedAnchorTime = 0;
        }

        // Numbered work steps get a "(n/total)" label; everything else
        // (fonts, config) shows its plain translated phase.
        if (phase.equals("Installing AI platform")) {
            window.setStatus(i18n.get("Installing AI platform")
                    + " (" + platformStep + "/" + totalSteps + ")");
        } else if (phase.startsWith("Pulling")) {
            window.setStatus(i18n.get("Installing AI models")
                    + " (" + modelStep + "/" + totalSteps + ")");
        } else if (phase.equals("Installing browser runtime")) {
            window.setStatus(i18n.get("Installing browser runtime")
                    + " (" + browserStep + "/" + totalSteps + ")");
        } else {
            window.setStatus(i18n.get(phase));
        }

        // Drive the progress bar + speed from the detail. A detail with no
        // percentage (a status word like "verifying", or a phase transition)
        // means there is nothing downloading right now, so the speed readout
        // is cleared rather than left showing a stale "0 B/s".
        int pct = SetupDetailParser.parsePercent(detail);
        // Downloaded/total figures ride verbatim next to the bar; null (no
        // figures in this detail) clears the readout rather than leaving a
        // stale position on screen.
        window.setByteFigures(pct < 0 ? null : SetupDetailParser.parseByteFigures(detail));
        if (pct < 0) {
            // The browser-runtime step has long stretches with no percentage at
            // all (unzip + tar extraction emit nothing), so drive the bar into
            // its indeterminate shimmer instead of freezing on the idle dot.
            // Other phases just idle.
            if (phase.equals("Installing browser runtime")) {
                window.setProgress(-1);
            }
            window.setSpeed(-1);
            speedAnchorBytes = 0;
            speedAnchorTime = 0;
            return;
        }
        window.setProgress(pct / 100.0);

        long currentBytes = SetupDetailParser.parseProgressBytes(detail);
        if (currentBytes < 0) {
            // Bar-only progress (curl --progress-bar) carries no byte figures —
            // keep the bar moving but show no speed.
            window.setSpeed(-1);
            speedAnchorBytes = 0;
            speedAnchorTime = 0;
            return;
        }
        updateSpeedAnchor(currentBytes, now);
    }

    /**
     * Advances the download-speed anchor. The byte figure is coarse (whole MB
     * below 1 GB, 100 MB steps above), so mid-download samples routinely repeat
     * the same value. The anchor therefore only moves when the figure actually
     * advances — the speed is then the true average across the whole flat
     * stretch — and a repeated value keeps the last reading on screen instead
     * of flapping the label off and on.
     */
    private void updateSpeedAnchor(long currentBytes, long now) {
        if (speedAnchorTime == 0) {
            speedAnchorBytes = currentBytes;
            speedAnchorTime = now;
        } else if (currentBytes < speedAnchorBytes) {
            // Backwards jump (the next model restarting at 0%) — hide and
            // re-anchor so the new download measures fresh.
            window.setSpeed(-1);
            speedAnchorBytes = currentBytes;
            speedAnchorTime = now;
        } else if (currentBytes > speedAnchorBytes) {
            if (now - speedAnchorTime >= 500) {
                window.setSpeed((currentBytes - speedAnchorBytes) * 1000
                        / (now - speedAnchorTime));
                speedAnchorBytes = currentBytes;
                speedAnchorTime = now;
            }
        } else if (now - speedAnchorTime >= SPEED_STALL_HIDE_MS) {
            // Genuinely stalled (no advance for a long while) — better no
            // readout than a stale one.
            window.setSpeed(-1);
            speedAnchorTime = now;
        }
    }
}
