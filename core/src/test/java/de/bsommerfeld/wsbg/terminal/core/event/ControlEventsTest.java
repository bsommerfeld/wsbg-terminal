package de.bsommerfeld.wsbg.terminal.core.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ControlEventsTest {

    @Test
    void triggerAgentAnalysisEvent_shouldStorePrompt() {
        var event = new ControlEvents.TriggerAgentAnalysisEvent("analyze this");
        assertEquals("analyze this", event.prompt());
    }

    @Test
    void triggerAgentAnalysisEvent_shouldAllowNullPrompt() {
        var event = new ControlEvents.TriggerAgentAnalysisEvent(null);
        assertNull(event.prompt());
    }

    @Test
    void logEvent_twoArgConstructor_shouldStoreMessageAndType() {
        var event = new ControlEvents.LogEvent("msg", "ERROR");
        assertEquals("msg", event.message());
        assertEquals("ERROR", event.type());
    }

    @Test
    void logEvent_singleArgConstructor_shouldDefaultToInfo() {
        var event = new ControlEvents.LogEvent("msg");
        assertEquals("msg", event.message());
        assertEquals("INFO", event.type());
    }

    @Test
    void logEvent_equality_shouldMatchOnBothFields() {
        var a = new ControlEvents.LogEvent("msg", "WARN");
        var b = new ControlEvents.LogEvent("msg", "WARN");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
