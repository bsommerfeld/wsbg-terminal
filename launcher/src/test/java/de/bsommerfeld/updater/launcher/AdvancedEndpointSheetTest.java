package de.bsommerfeld.updater.launcher;

import de.bsommerfeld.updater.catalog.ModelCatalog;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The advanced sheet, driven through the REAL geometry of the model-choice
 * screen it lives on - the entry is pressed at its own bounds, the fields are
 * filled, and the answer is read the way the launcher reads it.
 *
 * <p>The layout assertions are not decoration: this screen is 320x330 and every
 * pixel is spoken for, so an entry that drifts under the OK button or a sheet
 * that stops swallowing clicks is a real regression with no other alarm.
 */
class AdvancedEndpointSheetTest {

    private static ModelChoicePanel panel() {
        List<ModelChoicePanel.Row> rows = new ArrayList<>();
        for (ModelCatalog tier : ModelCatalog.values()) {
            String tag = tier.tagFor(false);
            rows.add(new ModelChoicePanel.Row(tag, tier.displayName(), tier.quality(),
                    tier.speed(), "1,0 GB", tier.fitFor(64), false, "Passt gut", false));
        }
        ModelChoicePanel p = new ModelChoicePanel(rows, ModelCatalog.DEFAULT.tagFor(false),
                new ModelChoicePanel.Labels("t", "q", "s", "Ok", "Ohne MLX", "Erweitert"),
                new AdvancedEndpointSheet.Labels("Eigener KI-Server", "Adresse", "Modell",
                        "Schlüssel", "Testen", "Frage ...", "Antwortet", "Keine Antwort",
                        "Es wird dann nichts geladen", "Nicht für Remote-Anbieter geeignet. Kosten können stark variieren."),
                new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), v -> { });
        p.setSize(320, 330);
        return p;
    }

    @Test
    void noEndpointUntilBothAddressAndModelAreThere() {
        // The same rule the app applies when it resolves the config. A sheet
        // that answered with half an endpoint would produce an install the user
        // believes is remote while the local model quietly runs.
        ModelChoicePanel p = panel();
        assertNull(p.chosenEndpoint(), "an untouched sheet answers nothing");

        AdvancedEndpointSheet sheet = new AdvancedEndpointSheet(p, labels());
        sheet.urlField.setText("192.168.1.20:11434");
        assertNull(sheet.endpoint(), "address without a model");

        sheet.urlField.setText("");
        sheet.modelField.setText("qwen3:32b");
        assertNull(sheet.endpoint(), "model without an address");

        sheet.urlField.setText("192.168.1.20:11434");
        assertNotNull(sheet.endpoint());
    }

    @Test
    void theAddressIsNormalizedTheSameWayTheAppWillReadIt() {
        AdvancedEndpointSheet sheet = new AdvancedEndpointSheet(panel(), labels());
        sheet.urlField.setText("  192.168.1.20:11434/ ");
        sheet.modelField.setText(" qwen3:32b ");
        AdvancedEndpointSheet.Endpoint e = sheet.endpoint();

        assertEquals("http://192.168.1.20:11434", e.url());
        assertEquals("qwen3:32b", e.model());
        assertEquals("", e.auth(), "no key typed is no key sent");
        assertEquals("ollama", e.api(), "untested: the protocol that loses nothing");
    }

    @Test
    void theEntrySitsBesideTheConfirmButton_neverUnderIt() {
        ModelChoicePanel p = panel();
        Rectangle entry = p.advancedBounds();
        Rectangle ok = p.okBounds();

        assertTrue(entry.x > 0, "on screen");
        assertTrue(entry.x + entry.width <= ok.x,
                "the entry must not run under the OK button");
        assertFalse(entry.intersects(ok));
    }

    @Test
    void pressingTheEntryOpensTheSheetAndItSwallowsTheStack() {
        ModelChoicePanel p = panel();
        Rectangle entry = p.advancedBounds();

        // Before: the middle of the card stack is not a control, so the window
        // can be dragged there.
        assertFalse(p.bodyHitsControl(160, 150));

        p.pressBody((int) entry.getCenterX(), (int) entry.getCenterY());

        // After: everything over the body belongs to the sheet - a drag must
        // not slide the launcher out from under a field being typed in.
        assertTrue(p.bodyHitsControl(160, 150));
    }

    @Test
    void theWarningIsAGlyph_andItsCalloutTakesTheFieldsOutOfTheWay() {
        // The fields are real Swing children and Swing paints those AFTER
        // everything we paint - so an open callout that leaves them in place
        // gets the address drawn straight through it (seen in the render).
        AdvancedEndpointSheet sheet = new AdvancedEndpointSheet(panel(), labels());
        sheet.openNow();
        sheet.urlField.setText("192.168.1.20:11434");
        assertTrue(sheet.urlField.isVisible(), "fields are up while the sheet is");

        // The glyph sits right of the title; press it through the real geometry.
        sheet.press(sheet.warnBoundsForTest().x + 2, sheet.warnBoundsForTest().y + 2);
        assertFalse(sheet.urlField.isVisible(), "the callout must have the stage to itself");

        // Any press dismisses it, and the fields come back.
        sheet.press(10, 10);
        assertTrue(sheet.urlField.isVisible());
    }

    private static AdvancedEndpointSheet.Labels labels() {
        return new AdvancedEndpointSheet.Labels("Eigener KI-Server", "Adresse", "Modell",
                "Schlüssel", "Testen", "Frage ...", "Antwortet", "Keine Antwort",
                "Es wird dann nichts geladen",
                "Nicht für Remote-Anbieter geeignet. Kosten können stark variieren.");
    }
}
