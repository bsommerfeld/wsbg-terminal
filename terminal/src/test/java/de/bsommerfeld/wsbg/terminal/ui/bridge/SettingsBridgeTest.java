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

    // ------------------------------------------------------------------
    // Advanced: the AI endpoint
    // ------------------------------------------------------------------

    @Test
    void endpointDefaultsAreTheManagedRuntime() {
        var snap = SettingsBridge.snapshot(new GlobalConfig());
        assertEquals("managed", snap.get("aiEndpointMode"));
        assertEquals("", snap.get("aiEndpointUrl"));
        assertEquals(0, snap.get("aiEndpointContext"));
        assertEquals("", snap.get("aiModelTag"), "empty = the managed default tier");
        assertFalse(((java.util.List<?>) snap.get("aiModelTiers")).isEmpty(),
                "the panel needs the same tiers the installer pulls from");
    }

    @Test
    void theModeIsReportedAsRESOLVED_notAsTyped() {
        // A half-filled remote setup runs as managed. The panel must show what
        // is in force, or the user stares at a switch that is on while the
        // local model answers.
        GlobalConfig c = new GlobalConfig();
        assertTrue(SettingsBridge.apply(c, "aiEndpointMode", "remote"));
        assertEquals("managed", SettingsBridge.snapshot(c).get("aiEndpointMode"),
                "no address and no model yet");

        assertTrue(SettingsBridge.apply(c, "aiEndpointUrl", "192.168.1.20:11434"));
        assertTrue(SettingsBridge.apply(c, "aiEndpointModel", "qwen3:32b"));
        assertEquals("remote", SettingsBridge.snapshot(c).get("aiEndpointMode"));
    }

    @Test
    void theAddressIsNormalizedOnTheWayIn() {
        // What the panel echoes back has to be what the endpoint will call -
        // otherwise the connection test and the running app disagree.
        GlobalConfig c = new GlobalConfig();
        assertTrue(SettingsBridge.apply(c, "aiEndpointUrl", "  192.168.1.20:11434/ "));
        assertEquals("http://192.168.1.20:11434", c.getAgent().getEndpointUrl());
    }

    @Test
    void anythingButRemoteIsManaged() {
        GlobalConfig c = new GlobalConfig();
        assertTrue(SettingsBridge.apply(c, "aiEndpointMode", "remote"));
        assertEquals("remote", c.getAgent().getEndpointMode());
        assertTrue(SettingsBridge.apply(c, "aiEndpointMode", "nonsense"));
        assertEquals("managed", c.getAgent().getEndpointMode(),
                "a garbage value must never leave the app pointing nowhere");
    }

    @Test
    void negativeLimitsDegradeToTheDefault() {
        GlobalConfig c = new GlobalConfig();
        assertTrue(SettingsBridge.apply(c, "aiEndpointContext", -4096));
        assertTrue(SettingsBridge.apply(c, "aiEndpointSlots", -1));
        assertEquals(0, c.getAgent().getEndpointContextTokens());
        assertEquals(0, c.getAgent().getEndpointParallelism());
    }

    @Test
    void theManagedModelTagStaysFamilyGated() {
        // Same gate the launcher applies when it reads this key: an unknown tag
        // would otherwise reach 'ollama pull' verbatim on the next start.
        GlobalConfig c = new GlobalConfig();
        assertFalse(SettingsBridge.apply(c, "aiModelTag", "llama3:70b"));
        assertEquals("", c.getAgent().getModelTag());

        assertTrue(SettingsBridge.apply(c, "aiModelTag", "gemma4:e2b"));
        assertEquals("gemma4:e2b", c.getAgent().getModelTag());

        assertTrue(SettingsBridge.apply(c, "aiModelTag", ""), "empty = back to automatic");
        assertEquals("", c.getAgent().getModelTag());
    }

    @Test
    void theProtocolKeyOnlyKnowsTwoAnswers() {
        GlobalConfig c = new GlobalConfig();
        assertEquals("ollama", SettingsBridge.snapshot(c).get("aiEndpointApi"));

        assertTrue(SettingsBridge.apply(c, "aiEndpointApi", "openai"));
        assertEquals("openai", c.getAgent().getEndpointApi());

        assertTrue(SettingsBridge.apply(c, "aiEndpointApi", "grpc-or-whatever"));
        assertEquals("ollama", c.getAgent().getEndpointApi(),
                "the safe end of a garbage value is the protocol that loses nothing");
    }

    @Test
    void theRemoteModelTagIsNotGated() {
        // The remote store is the user's; our family list says nothing about it.
        GlobalConfig c = new GlobalConfig();
        assertTrue(SettingsBridge.apply(c, "aiEndpointModel", "llama3.3:70b"));
        assertEquals("llama3.3:70b", c.getAgent().getEndpointModel());
    }

    @Test
    void unknownKeyIsIgnored() {
        assertFalse(SettingsBridge.apply(new GlobalConfig(), "bogus", "x"));
    }
}
