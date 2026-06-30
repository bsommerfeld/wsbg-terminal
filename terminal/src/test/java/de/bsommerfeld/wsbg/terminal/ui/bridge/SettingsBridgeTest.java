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
        assertEquals(true, snap.get("analyzeImages"), "image analysis on by default");
        assertEquals(true, snap.get("suppressRedundant"), "redundancy filter on by default");
        assertEquals("de", snap.get("language"));
        assertEquals(true, snap.get("autoUpdate"));
    }

    @Test
    void suppressRedundantMapsToFlag() {
        GlobalConfig c = new GlobalConfig();
        assertTrue(SettingsBridge.apply(c, "suppressRedundant", false));
        assertFalse(c.getHeadlines().isSuppressRedundant());
        assertEquals(false, SettingsBridge.snapshot(c).get("suppressRedundant"));

        assertTrue(SettingsBridge.apply(c, "suppressRedundant", "true"));
        assertTrue(c.getHeadlines().isSuppressRedundant());
    }

    @Test
    void booleanKeysAcceptBoolAndString() {
        GlobalConfig c = new GlobalConfig();
        assertTrue(SettingsBridge.apply(c, "autoUpdate", false));
        assertFalse(c.getUser().isAutoUpdate());
        assertTrue(SettingsBridge.apply(c, "autoUpdate", "true"));
        assertTrue(c.getUser().isAutoUpdate());

        assertTrue(SettingsBridge.apply(c, "analyzeImages", false));
        assertFalse(c.getHeadlines().isAnalyzeImages());
        assertTrue(SettingsBridge.apply(c, "analyzeImages", "true"));
        assertTrue(c.getHeadlines().isAnalyzeImages());
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
