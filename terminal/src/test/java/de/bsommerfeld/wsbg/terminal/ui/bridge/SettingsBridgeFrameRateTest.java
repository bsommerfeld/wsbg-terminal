package de.bsommerfeld.wsbg.terminal.ui.bridge;

import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.UserConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The redraw-rate setting: two values, the default for anything else, and it travels in the snapshot. */
class SettingsBridgeFrameRateTest {

    @Test
    void frameRate_defaultsToTheDisplay() {
        assertEquals(UserConfig.FRAME_RATE_DISPLAY, new UserConfig().getFrameRate());
    }

    @Test
    void apply_acceptsTheTwoValuesAndFallsBackForGarbage() {
        GlobalConfig config = new GlobalConfig();
        assertTrue(SettingsBridge.apply(config, "frameRate", "60"));
        assertEquals(UserConfig.FRAME_RATE_60, config.getUser().getFrameRate());
        assertTrue(SettingsBridge.apply(config, "frameRate", "display"));
        assertEquals(UserConfig.FRAME_RATE_DISPLAY, config.getUser().getFrameRate());
        assertTrue(SettingsBridge.apply(config, "frameRate", "144"));
        assertEquals(UserConfig.FRAME_RATE_DISPLAY, config.getUser().getFrameRate(),
                "an unknown value is the default, never a stored surprise");
    }

    @Test
    void snapshot_carriesTheFrameRate() {
        GlobalConfig config = new GlobalConfig();
        config.getUser().setFrameRate("60");
        Map<String, Object> out = SettingsBridge.snapshot(config);
        assertEquals("60", out.get("frameRate"));
        assertFalse(out.containsKey("frame-rate"), "the wire key is camelCase like the others");
    }
}
