package de.bsommerfeld.updater.launcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Throwaway: opens the real launcher window on the real model-choice screen,
 * with the RAM figure faked via {@code -Ddummy.ram=<gb>} so every fit verdict
 * can be looked at. Not a test - delete after the visual review.
 */
final class ModelChoiceDummyView {

    public static void main(String[] args) throws Exception {
        // The same Java2D weiche LauncherMain sets before AWT initializes:
        // on Metal the per-pixel-translucent window presents empty under
        // animation load and blinks away to the desktop. A harness that
        // skips this does not show the launcher, it shows a Metal artefact.
        System.setProperty("sun.java2d.metal", "false");

        long ram = Long.getLong("dummy.ram", 48);
        boolean mlx = System.getProperty("dummy.mlx", "true").equals("true");
        String lang = System.getProperty("dummy.lang", "de");

        List<ModelChoicePanel.Row> rows = new ArrayList<>();
        String recTag = ModelCatalog.recommend(ram).tagFor(mlx);
        for (ModelCatalog tier : ModelCatalog.values()) {
            String tag = tier.tagFor(mlx);
            boolean rec = tag.equals(recTag);
            ModelCatalog.Fit fit = tier.fitFor(ram);
            String verdict = LauncherI18n.translate(switch (fit) {
                case COMFORTABLE -> rec ? "Recommended" : "Good fit";
                case TIGHT -> "Tight fit";
                case TOO_LARGE -> "Too large";
            }, lang);
            String size = String.format("de".equals(lang) ? Locale.GERMAN : Locale.ROOT,
                    "%.1f GB", tier.diskGbFor(mlx));
            rows.add(new ModelChoicePanel.Row(tag, tier.displayName(), tier.quality(),
                    tier.speed(), size, fit, rec, verdict));
        }

        ModelChoicePanel.Labels labels = new ModelChoicePanel.Labels(
                LauncherI18n.translate("Choose your AI model", lang),
                LauncherI18n.translate("Quality", lang),
                LauncherI18n.translate("Speed", lang),
                LauncherI18n.translate("The recommendation fits your machine", lang),
                LauncherI18n.translate("OK", lang));

        LauncherWindow window = new LauncherWindow();
        javax.swing.SwingUtilities.invokeAndWait(() -> window.setVisible(true));
        Thread.sleep(600);
        String chosen = window.showModelChoice(rows, recTag, labels).get();
        System.out.println("chosen: " + chosen);
        System.exit(0);
    }
}
