package de.bsommerfeld.updater.launcher;

import de.bsommerfeld.updater.catalog.ModelCatalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Throwaway: plays the COMPLETE first-run launcher flow against the real
 * {@link LauncherWindow} - language choice, update channel, model choice,
 * update download, environment setup, launch - with every side effect faked.
 * Nothing is downloaded, installed, written to the real app directory or
 * started; it exists purely so the whole sequence can be screen-recorded and
 * clicked through on demand. Not a test - delete after the recording.
 *
 * <p>Knobs (all optional):
 * <pre>
 *   -Ddemo.pace=1.0     speed multiplier; 0.5 = twice as fast
 *   -Ddemo.ram=48       faked machine RAM in GB (drives the fit verdicts)
 *   -Ddemo.mlx=true     faked Apple Silicon (drives the model tags/sizes)
 *   -Ddemo.hold=4000    ms the finished "Launching..." screen stays up
 * </pre>
 */
final class LauncherDemo {

    /** Fake update-download steps before the three environment steps. */
    private static final int DOWNLOAD_STEPS = 5;

    private static double pace;
    private static LauncherWindow window;
    private static LauncherI18n i18n;
    private static SessionLog log;

    public static void main(String[] args) throws Exception {
        // The same Java2D weiche LauncherMain sets before AWT initializes:
        // on Metal the per-pixel-translucent window presents empty under
        // animation load and blinks away to the desktop.
        System.setProperty("apple.awt.application.name", "WSBG Terminal");
        System.setProperty("sun.java2d.metal", "false");
        // ... and the accessory-app switch, so the demo window behaves exactly
        // like the real one (no dock tile, floats instead).
        System.setProperty("apple.awt.UIElement", "true");

        pace = Double.parseDouble(System.getProperty("demo.pace", "1.0"));
        long ram = Long.getLong("demo.ram", 48);
        boolean mlx = System.getProperty("demo.mlx", "true").equals("true");

        // A scratch app dir, so the demo never touches the real install:
        // config.toml, session log and rotation all land in /tmp.
        Path appDir = Files.createTempDirectory("wsbg-launcher-demo");
        Files.createDirectories(appDir.resolve("logs/launcher"));

        log = new SessionLog(appDir);
        log.log("Launcher demo started (appDir=" + appDir + ")");
        i18n = new LauncherI18n(appDir);

        window = new LauncherWindow();
        javax.swing.SwingUtilities.invokeAndWait(() -> window.setVisible(true));
        sleep(900);

        // Hands-free: drive the choice screens with a Robot and photograph the
        // window along the way. For verifying the flow without a human hand -
        // and for a recording that needs no operator.
        if (System.getProperty("demo.capture") != null) {
            Thread.ofPlatform().daemon().start(LauncherDemo::autoDrive);
        }

        // The choice screens need real clicks; -Ddemo.choices=false jumps
        // straight into the progress pipeline (handy to re-record just that).
        if (!"false".equals(System.getProperty("demo.choices"))) {
            i18n.switchTo(languageChoice());
            channelChoice();
            modelChoice(ram, mlx);
        }

        updatePhase();
        environmentPhase();
        launchPhase();

        sleep(Long.getLong("demo.hold", 4000L));
        System.exit(0);
    }

    // =====================================================================
    // Choice screens - the real panels, the real morph, the real futures
    // =====================================================================

    private static String languageChoice() throws Exception {
        List<LanguageChoicePanel.Row> rows = new ArrayList<>();
        for (String code : LauncherI18n.LANGUAGES) {
            rows.add(new LanguageChoicePanel.Row(code,
                    Locale.forLanguageTag(code).getDisplayLanguage(Locale.forLanguageTag(code)),
                    LauncherI18n.translate("Choose your language", code),
                    LauncherI18n.translate("You can change this later in the settings", code),
                    LauncherI18n.translate("OK", code)));
        }
        String chosen = window.showLanguageChoice(rows, "de").get();
        log.log("[demo] language: " + chosen);
        return chosen;
    }

    private static void channelChoice() throws Exception {
        List<ChannelChoicePanel.Row> rows = List.of(
                new ChannelChoicePanel.Row(ChannelSelection.NO,
                        i18n.get("Stable"), i18n.get("Risk management")),
                new ChannelChoicePanel.Row(ChannelSelection.YES,
                        i18n.get("Experimental"), i18n.get("100x leverage")));

        ChannelChoicePanel.Labels labels = new ChannelChoicePanel.Labels(
                i18n.get("Which updates do you want?"),
                i18n.get("You can change this later in the settings"),
                i18n.get("OK"));

        log.log("[demo] channel: " + window.showChannelChoice(rows, ChannelSelection.NO, labels).get());
    }

    private static void modelChoice(long ram, boolean mlx) throws Exception {
        List<ModelChoicePanel.Row> rows = new ArrayList<>();
        String recTag = ModelCatalog.recommend(ram).tagFor(mlx);
        for (ModelCatalog tier : ModelCatalog.values()) {
            String tag = tier.tagFor(mlx);
            boolean rec = tag.equals(recTag);
            ModelCatalog.Fit fit = tier.fitFor(ram);
            String verdict = i18n.get(switch (fit) {
                case COMFORTABLE -> rec ? "Recommended" : "Good fit";
                case TIGHT -> "Tight fit";
                case TOO_LARGE -> "Too large";
            });
            String size = String.format(
                    "de".equals(i18n.language()) ? Locale.GERMAN : Locale.ROOT,
                    "%.1f GB", tier.diskGbFor(mlx));
            rows.add(new ModelChoicePanel.Row(tag, tier.displayName(), tier.quality(),
                    tier.speed(), size, fit, rec, verdict, tag.endsWith("-mlx")));
        }

        ModelChoicePanel.Labels labels = new ModelChoicePanel.Labels(
                i18n.get("Choose your AI model"),
                i18n.get("Quality"),
                i18n.get("Speed"),
                i18n.get("OK"),
                i18n.get("Without MLX"));

        log.log("[demo] model: " + window.showModelChoice(rows, recTag, labels).get());
    }

    // =====================================================================
    // Faked pipeline - same labels, same numbering, same readouts
    // =====================================================================

    /**
     * The update phase as {@code LauncherMain.runUpdatePhase} renders it:
     * translated phase plus "(n/total)", the bar snapped back to the dot on
     * every transition, and byte/speed readouts on the two real downloads.
     */
    private static void updatePhase() {
        int total = DOWNLOAD_STEPS + 3;
        phase("Checking for updates", 0, total);
        sleep(1400);

        phase("Downloading update", 1, total);
        download(78_000_000L, "78 MB", 9_000_000L);

        phase("Extracting files", 2, total);
        ramp(1100);

        phase("Downloading dependencies", 3, total);
        download(214_000_000L, "214 MB", 12_000_000L);

        phase("Extracting dependencies", 4, total);
        ramp(1500);

        phase("Verifying integrity", 5, total);
        ramp(900);

        phase("Cleaning up", 0, 0);
        sleep(700);
    }

    /** Sets a numbered (or plain, when {@code step <= 0}) status line. */
    private static void phase(String key, int step, int total) {
        window.resetProgress();
        window.setSpeed(-1);
        window.setByteFigures(null);
        String label = i18n.get(key);
        if (step > 0) label += " (" + step + "/" + total + ")";
        log.log("[demo] " + label);
        window.setStatus(label);
    }

    /** A download with a moving bar, live byte figures and a wobbling speed. */
    private static void download(long totalBytes, String totalLabel, long bytesPerSec) {
        for (int pct = 0; pct <= 100; pct += 2) {
            long done = totalBytes * pct / 100;
            window.setProgress(pct / 100.0);
            window.setByteFigures(figure(done) + " / " + totalLabel);
            window.setSpeed(bytesPerSec + (pct % 7) * 400_000L);
            sleep(90);
        }
        window.setByteFigures(null);
        window.setSpeed(-1);
        sleep(250);
    }

    /** A short determinate sweep for the no-byte-figure phases. */
    private static void ramp(long millis) {
        int ticks = 40;
        for (int i = 1; i <= ticks; i++) {
            window.setProgress(i / (double) ticks);
            sleep(millis / ticks);
        }
        sleep(200);
    }

    private static String figure(long bytes) {
        return bytes >= 1_000_000_000L
                ? String.format(Locale.ROOT, "%.1f GB", bytes / 1_000_000_000.0)
                : (bytes / 1_000_000L) + " MB";
    }

    /**
     * The environment phase through the REAL {@link SetupProgressAdapter}, fed
     * the same {@code (phase, detail)} events the setup script emits - so the
     * numbering, the pips, the speed anchor and the indeterminate shimmer all
     * behave exactly as in production.
     */
    private static void environmentPhase() {
        window.resetProgress();
        window.setSpeed(-1);
        SetupProgressAdapter setup = new SetupProgressAdapter(window, i18n, log, DOWNLOAD_STEPS);

        // Step n+1: the AI platform.
        setup.accept("Installing AI platform", null);
        sleep(800);
        for (int pct = 0; pct <= 100; pct += 4) {
            setup.accept("Installing AI platform",
                    pct + "% — " + (620L * pct / 100) + " MB / 620 MB");
            sleep(110);
        }

        // Step n+2: the model pulls - two models, one pip each.
        setup.accept("ModelCount", "2/1");
        pull(setup, "Pulling qwen3:14b", 8_100L, "8.1 GB", 1);
        setup.accept("ModelCount", "2/2");
        pull(setup, "Pulling embeddinggemma:300m", 620L, "620 MB", 2);

        // Step n+3: the browser runtime - long stretches without a percentage,
        // which is exactly where the indeterminate shimmer shows up.
        setup.accept("Installing browser runtime", null);
        sleep(900);
        for (int pct = 0; pct <= 100; pct += 5) {
            setup.accept("Installing browser runtime",
                    pct + "% — " + (150L * pct / 100) + " MB / 150 MB");
            sleep(120);
        }
        setup.accept("Installing browser runtime", "unpacking");
        sleep(2500);

        // The unnumbered tail.
        setup.accept("Installing fonts", null);
        sleep(1200);
        setup.accept("Cleaning up old models", null);
        sleep(1000);
    }

    /** One model pull, emitted in the script's rich detail format. */
    private static void pull(SetupProgressAdapter setup, String phase,
            long totalMb, String totalLabel, int index) {
        setup.accept(phase, null);
        sleep(600);
        for (int pct = 0; pct <= 100; pct += 2) {
            long doneMb = totalMb * pct / 100;
            String done = totalMb >= 1000
                    ? String.format(Locale.ROOT, "%.1f GB", doneMb / 1000.0)
                    : doneMb + " MB";
            setup.accept(phase, pct + "% — " + done + " / " + totalLabel);
            sleep(index == 1 ? 130 : 70);
        }
        setup.accept(phase, "verifying");
        sleep(900);
    }

    private static void launchPhase() {
        window.clearModelPips();
        window.setStatus(i18n.get("Launching application"));
        window.setProgress(1.0);
        log.log("[demo] launch (no process started)");
    }

    /**
     * Clicks through the three choice screens with a Robot, flipping the model
     * stack on the way, and writes a photo of the window at each interesting
     * moment into {@code -Ddemo.capture=<dir>}.
     */
    private static void autoDrive() {
        try {
            java.awt.Robot robot = new java.awt.Robot();
            java.io.File dir = new java.io.File(System.getProperty("demo.capture"));
            dir.mkdirs();

            shoot(robot, dir, "live-1-language");
            clickOk(robot);
            Thread.sleep(150);
            shoot(robot, dir, "live-2-dissolve");
            Thread.sleep(1200);

            shoot(robot, dir, "live-3-channel");
            clickOk(robot);
            Thread.sleep(1400);

            shoot(robot, dir, "live-4-model");
            java.awt.Rectangle b = window.getBounds();
            // The up chevron - one rung up the ladder.
            click(robot, b.x + b.width - 18 - 33 - 11, b.y + 261);
            Thread.sleep(80);
            shoot(robot, dir, "live-5-model-flipping");
            Thread.sleep(700);
            shoot(robot, dir, "live-6-model-flipped");
            // The peeking edges above the front card - one more rung up.
            click(robot, b.x + b.width / 2, b.y + 100);
            Thread.sleep(700);
            shoot(robot, dir, "live-6b-model-peek-click");
            // And the wheel, over the card itself.
            robot.mouseMove(b.x + b.width / 2, b.y + 150);
            robot.mouseWheel(1);
            Thread.sleep(700);
            shoot(robot, dir, "live-6c-model-wheel");
            clickOk(robot);
            Thread.sleep(1400);
            shoot(robot, dir, "live-7-progress");
            // The rest of the pipeline, sampled - the model pulls are the
            // frames where the pips and the byte figures share a row.
            for (int i = 0; i < 12; i++) {
                Thread.sleep(1000);
                shoot(robot, dir, "live-8-pipeline-" + i);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** A plain click at a screen point. */
    private static void click(java.awt.Robot robot, int x, int y) throws Exception {
        robot.mouseMove(x, y);
        Thread.sleep(80);
        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
    }

    private static void clickOk(java.awt.Robot robot) throws Exception {
        java.awt.Rectangle b = window.getBounds();
        robot.mouseMove(b.x + b.width - 18 - 37, b.y + b.height - 14 - 15);
        Thread.sleep(120);
        // Twice: an unfocused window spends the first click on becoming the
        // active one - an automation detail, not something a human hits.
        for (int i = 0; i < 2; i++) {
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            Thread.sleep(120);
        }
    }

    private static void shoot(java.awt.Robot robot, java.io.File dir, String name)
            throws Exception {
        javax.imageio.ImageIO.write(robot.createScreenCapture(window.getBounds()),
                "png", new java.io.File(dir, name + ".png"));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(Math.max(0, (long) (millis * pace)));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private LauncherDemo() {
    }
}
