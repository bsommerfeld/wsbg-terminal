package de.bsommerfeld.updater;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PostUpdateScriptTest {

    @Test
    void interpreterFollowsTheExtension() {
        assertEquals(List.of("bash", Path.of("bin/setup.sh").toString()), PostUpdateScript.command(Path.of("bin/setup.sh")));
        assertEquals("powershell", PostUpdateScript.command(Path.of("bin/setup.ps1")).getFirst());
        assertEquals(List.of("cmd", "/c", Path.of("bin/setup.BAT").toString()), PostUpdateScript.command(Path.of("bin/setup.BAT")));
        assertEquals(List.of(Path.of("bin/setup").toString()), PostUpdateScript.command(Path.of("bin/setup")));
    }

    @Test
    void warningsAreSuccess() {
        assertTrue(PostUpdateScript.succeeded(0));
        assertTrue(PostUpdateScript.succeeded(PostUpdateScript.EXIT_WITH_WARNINGS));
        assertFalse(PostUpdateScript.succeeded(1));
    }
}
