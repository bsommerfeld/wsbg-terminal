package de.bsommerfeld.updater.launcher;

import de.bsommerfeld.updater.catalog.ModelCatalog;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "without MLX" lever on the model-choice stack: pressed through the REAL
 * geometry ({@code toggleBounds()} + {@code pressBody}), so a layout change
 * that parks the toggle on top of another tap zone or off-screen fails here.
 */
class ModelChoicePanelToggleTest {

    private static ModelChoicePanel panel(boolean appleSilicon, String preselectTag) {
        List<ModelChoicePanel.Row> rows = new ArrayList<>();
        for (ModelCatalog tier : ModelCatalog.values()) {
            String tag = tier.tagFor(appleSilicon);
            rows.add(new ModelChoicePanel.Row(tag, tier.displayName(), tier.quality(),
                    tier.speed(), "1,0 GB", tier.fitFor(64), false, "Passt gut",
                    tag.endsWith("-mlx")));
        }
        ModelChoicePanel p = new ModelChoicePanel(rows, preselectTag,
                new ModelChoicePanel.Labels("t", "q", "s", "Ok", "Ohne MLX", "Erweitert"),
                new AdvancedEndpointSheet.Labels("Eigener KI-Server", "Adresse",
                        "Modell", "Schlüssel", "Testen", "Frage ...", "Antwortet",
                        "Keine Antwort", "Es wird dann nichts geladen", "Nicht für Remote-Anbieter geeignet. Kosten können stark variieren."),
                new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), v -> { });
        p.setSize(320, 330);
        return p;
    }

    @Test
    void toggleFlipsTheConfirmedTagBetweenMlxAndBase() {
        ModelChoicePanel p = panel(true, ModelCatalog.E4B.tagFor(true));
        assertEquals("gemma4:e4b-mlx", p.confirmedValue(), "MLX stays the default");

        Rectangle t = p.toggleBounds();
        assertTrue(t.width > 0, "toggle must have room in the nav strip");
        p.pressBody(t.x + 2, t.y + t.height / 2);
        assertEquals("gemma4:e4b", p.confirmedValue(),
                "the lever confirms the BASE tag — the suffix is dropped here and nowhere re-added");

        p.pressBody(t.x + 2, t.y + t.height / 2);
        assertEquals("gemma4:e4b-mlx", p.confirmedValue(), "pressing again returns to MLX");
    }

    @Test
    void toggleZoneStaysClearOfDotsAndChevrons() {
        // The panel already triple-books its click zones (peek edges, dots,
        // chevrons); the toggle lives in the strip's free middle and must not
        // overlap either neighbour.
        ModelChoicePanel p = panel(true, ModelCatalog.E4B.tagFor(true));
        Rectangle t = p.toggleBounds();
        int lastDotX = 18 + 4 + (ModelCatalog.values().length - 1) * 14;
        assertTrue(t.x > lastDotX + 7, "toggle must start right of the page dots' hit zone");
        int chevronLeft = 320 - 18 - 2 * (2 * 11) - 6; // upBounds().x with CHEVRON_R = 11
        assertTrue(t.x + t.width < chevronLeft, "toggle must end left of the chevrons");
    }

    @Test
    void tiersWithoutAnMlxTwinShowNoLever() {
        // Granite in front: there IS no choice, so the control disappears
        // instead of standing around inert — a press where it would sit
        // must not change the confirmed tag.
        ModelChoicePanel p = panel(true, ModelCatalog.GRANITE_3B.tagFor(true));
        assertEquals("granite4.1:3b", p.confirmedValue());
        Rectangle t = p.toggleBounds();
        assertFalse(p.bodyHitsControl(t.x + 2, t.y + t.height / 2),
                "no toggle hit zone on a card without an MLX twin");
        p.pressBody(t.x + 2, t.y + t.height / 2);
        assertEquals("granite4.1:3b", p.confirmedValue());
    }

    @Test
    void nonAppleMachinesNeverSeeTheLever() {
        // Windows/Linux rows carry no -mlx tag, so the lever cannot appear on
        // ANY card — derived from the effective tags, not a platform flag.
        ModelChoicePanel p = panel(false, ModelCatalog.E4B.tagFor(false));
        Rectangle t = p.toggleBounds();
        assertFalse(p.bodyHitsControl(t.x + 2, t.y + t.height / 2));
        p.pressBody(t.x + 2, t.y + t.height / 2);
        assertEquals("gemma4:e4b", p.confirmedValue());
    }
}
