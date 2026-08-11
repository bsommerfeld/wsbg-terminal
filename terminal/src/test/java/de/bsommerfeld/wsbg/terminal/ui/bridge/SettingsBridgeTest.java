package de.bsommerfeld.wsbg.terminal.ui.bridge;

import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Settings view backend: the config-key mapping ({@link SettingsBridge#apply})
 * and the snapshot it pushes ({@link SettingsBridge#snapshot}). The socket /
 * persistence wiring is exercised live; here we pin the pure mapping.
 */
class SettingsBridgeTest {

    @Test
    void snapshotReflectsDefaults() {
        GlobalConfig c = new GlobalConfig();
        var snap = SettingsBridge.snapshot(c);
        assertFalse(snap.containsKey("analyzeImages"), "image analysis is no user setting anymore");
        assertEquals("de", snap.get("language"));
        assertEquals(true, snap.get("autoUpdate"));
        assertEquals(false, snap.get("experimentalUpdates"),
                "an unanswered install reads as off — the toggle only knows two states");
    }

    @Test
    void experimentalUpdatesTogglesTheTriStateStringTheLauncherReads() {
        GlobalConfig c = new GlobalConfig();
        assertEquals("", c.getUser().getExperimentalUpdates(), "fresh config: never asked");

        assertTrue(SettingsBridge.apply(c, "experimentalUpdates", true));
        assertEquals("yes", c.getUser().getExperimentalUpdates());

        assertTrue(SettingsBridge.apply(c, "experimentalUpdates", false));
        assertEquals("no", c.getUser().getExperimentalUpdates(),
                "switching off must write an explicit 'no', not clear the answer — "
                + "an empty value would put the launcher's question again");
    }


    @Test
    void booleanKeysAcceptBoolAndString() {
        GlobalConfig c = new GlobalConfig();
        assertTrue(SettingsBridge.apply(c, "autoUpdate", false));
        assertFalse(c.getUser().isAutoUpdate());
        assertTrue(SettingsBridge.apply(c, "autoUpdate", "true"));
        assertTrue(c.getUser().isAutoUpdate());
    }

    @Test
    void analyzeImagesIsNoSettingAnymore() {
        GlobalConfig c = new GlobalConfig();
        assertFalse(SettingsBridge.apply(c, "analyzeImages", true), "removed key is ignored");
        assertFalse(c.getHeadlines().isAnalyzeImages(), "config stays untouched");
    }

    @Test
    void languageOnlyAcceptsKnownCodes() {
        GlobalConfig c = new GlobalConfig();
        assertTrue(SettingsBridge.apply(c, "language", "en"));
        assertEquals("en", c.getUser().getLanguage());
        assertFalse(SettingsBridge.apply(c, "language", "fr"), "unknown locale rejected");
        assertEquals("en", c.getUser().getLanguage(), "rejected value leaves config untouched");
    }

    @Test
    void unknownKeyIsIgnored() {
        assertFalse(SettingsBridge.apply(new GlobalConfig(), "bogus", "x"));
    }
}
