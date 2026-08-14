package de.bsommerfeld.updater.launcher;

import de.bsommerfeld.updater.catalog.ModelCatalog;

import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Throwaway: renders each launcher screen to a PNG for a visual review. */
final class ScreenShots {

    private static final int W = 320;
    private static final int H = 330;

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        File out = new File(args[0]);
        out.mkdirs();
        BufferedImage logo = renderLogo();

        LanguageChoicePanel lang = new LanguageChoicePanel(List.of(
                new LanguageChoicePanel.Row("de", "Deutsch", "Sprache wählen",
                        "Später in den Einstellungen änderbar", "Ok"),
                new LanguageChoicePanel.Row("en", "English", "Choose your language",
                        "You can change this later in the settings", "OK")),
                "de", logo, v -> {});
        write(lang, new File(out, "1-language.png"));

        ChannelChoicePanel channel = new ChannelChoicePanel(List.of(
                new ChannelChoicePanel.Row("no", "Stabil", "Risikomanagement"),
                new ChannelChoicePanel.Row("yes", "Experimentell", "100x Hebel")),
                "no", new ChannelChoicePanel.Labels("Welche Updates willst du?",
                        "Später in den Einstellungen änderbar", "Ok"),
                logo, v -> {});
        write(channel, new File(out, "2-channel.png"));

        ModelChoicePanel model = model(logo, 48);
        write(model, new File(out, "3-model-front.png"));

        // Mid-flip: one wheel notch, painted before the ease has settled.
        SwingUtilities.invokeAndWait(() -> model.dispatchEvent(new MouseWheelEvent(model,
                MouseWheelEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0, 100, 150,
                0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -1)));
        Thread.sleep(90);
        write(model, new File(out, "4-model-flipping.png"));
        Thread.sleep(600);
        write(model, new File(out, "5-model-settled.png"));

        // An 8 GB machine — the top tiers read "too large".
        write(model(logo, 8), new File(out, "6-model-small-machine.png"));

        ScreenTransition transition = new ScreenTransition(
                paint(lang), paint(model(logo, 48)));
        transition.setSize(W, H);
        transition.setProgress(0.5f);
        write(transition, new File(out, "7-transition-halfway.png"));

        System.out.println("wrote " + out);
        System.exit(0);
    }

    private static ModelChoicePanel model(BufferedImage logo, long ram) {
        List<ModelChoicePanel.Row> rows = new ArrayList<>();
        String recTag = ModelCatalog.recommend(ram).tagFor(true);
        for (ModelCatalog tier : ModelCatalog.values()) {
            String tag = tier.tagFor(true);
            boolean rec = tag.equals(recTag);
            ModelCatalog.Fit fit = tier.fitFor(ram);
            String verdict = switch (fit) {
                case COMFORTABLE -> rec ? "Empfohlen" : "Passt gut";
                case TIGHT -> "Passt knapp";
                case TOO_LARGE -> "Zu groß";
            };
            rows.add(new ModelChoicePanel.Row(tag, tier.displayName(), tier.quality(),
                    tier.speed(), String.format(Locale.GERMAN, "%.1f GB", tier.diskGbFor(true)),
                    fit, rec, verdict, tag.endsWith("-mlx")));
        }
        return new ModelChoicePanel(rows, recTag, new ModelChoicePanel.Labels(
                "Wähle dein KI-Modell", "Qualität", "Tempo", "Ok", "Ohne MLX"),
                logo, v -> {});
    }

    private static BufferedImage renderLogo() {
        JPanel panel = LogoRenderer.createPanel();
        panel.setSize(panel.getPreferredSize());
        BufferedImage image = new BufferedImage(panel.getWidth(), panel.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        panel.paint(g);
        g.dispose();
        return image;
    }

    private static BufferedImage paint(javax.swing.JComponent c) {
        c.setSize(W, H);
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        c.paint(g);
        g.dispose();
        return image;
    }

    private static void write(javax.swing.JComponent c, File file) throws Exception {
        ImageIO.write(paint(c), "png", file);
    }

    private ScreenShots() {
    }
}
