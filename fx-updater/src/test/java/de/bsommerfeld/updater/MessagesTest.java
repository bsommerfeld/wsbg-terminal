package de.bsommerfeld.updater;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class MessagesTest {

    private final Locale original = Locale.getDefault();

    @AfterEach
    void restore() {
        Locale.setDefault(original);
    }

    @Test
    void german() {
        Locale.setDefault(Locale.GERMANY);
        Messages messages = Messages.forDefaultLocale();
        assertEquals("Warte, bis WSBG Terminal beendet ist", messages.get("waiting", "WSBG Terminal"));
        assertEquals("Update wird geladen", messages.phase("Downloading update"));
        assertEquals("3,4 MB/s", messages.speed(3_400_000));
        assertEquals("Schritt 1 von 2", messages.get("step", 1, 2));
    }

    @Test
    void englishFallback_unknownKeysAndTokensShowThemselves() {
        Locale.setDefault(Locale.JAPAN);
        Messages messages = Messages.forDefaultLocale();
        assertEquals("Downloading update", messages.phase("Downloading update"));
        assertEquals("512 KB/s", messages.speed(512_000));
        assertEquals("no.such.key", messages.get("no.such.key"));
        assertEquals("Some future phase", messages.phase("Some future phase"));
    }
}
