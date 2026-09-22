package de.bsommerfeld.wsbg.terminal.ui;

import javafx.css.CssParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JavaFX swallows stylesheet errors as warnings at runtime; here they fail the
 * build.
 */
class StylesheetsTest {

    @Test
    void everyStylesheetParsesWithoutErrors() throws IOException {
        var errors = CssParser.errorsProperty();
        errors.clear();
        assertFalse(Stylesheets.urls().isEmpty());
        for (URL url : Stylesheets.urls()) {
            assertTrue(url != null, "stylesheet missing");
            assertFalse(new CssParser().parse(url).getRules().isEmpty(), url + " has no rules");
        }
        assertEquals(0, errors.size(), errors.toString());
    }
}
