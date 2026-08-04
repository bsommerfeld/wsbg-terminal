package de.bsommerfeld.wsbg.terminal.ui;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Dev-only dump: paints the close-confirm sheet on the app's own background, so
 * the sheet can be eyeballed without provoking a real close during a deep dive.
 * {@code CLOSE_SHEET_PREVIEW} names the output PNG.
 */
@Tag("integration")
class CloseConfirmDialogPreviewDump {

    @Test
    void dump() throws Exception {
        String out = System.getenv("CLOSE_SHEET_PREVIEW");
        if (out == null) return;

        final BufferedImage[] shot = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> shot[0] = paint());
        ImageIO.write(shot[0], "png", new File(out));
    }

    private static BufferedImage paint() {
        JComponent sheet = CloseConfirmDialog.previewSheet(
                "Die Redaktion arbeitet noch",
                "Der laufende Deep Dive lebt nur im Arbeitsspeicher. Beim Schließen "
                        + "ist er verloren und muss komplett neu erstellt werden.",
                "Trotzdem schließen", "Weiterarbeiten");

        // A real (offscreen) window: the layout only settles once the hierarchy
        // is realised, and per-pixel translucency needs a peer to be reported.
        JFrame host = new JFrame();
        host.setUndecorated(true);
        host.setLocation(-4000, -4000);
        host.setContentPane(sheet);
        host.pack();
        host.setVisible(true);

        Dimension d = sheet.getSize();
        BufferedImage img = new BufferedImage(d.width + 80, d.height + 80,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x0E, 0x0C, 0x0A));  // --bg, the window behind it
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.translate(40, 40);
        sheet.paint(g);
        g.dispose();
        host.dispose();
        return img;
    }
}
