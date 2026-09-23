package de.bsommerfeld.signals;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalScopeTest {

    private static final SignalKey<String> OPENED = SignalKey.of(SignalScopeTest.class, "opened");
    private static final SignalKey<Void> CLOSED = SignalKey.of(SignalScopeTest.class, "closed");

    private final List<String> heard = new ArrayList<>();
    private final SignalScope root = SignalScope.root(UnhandledSignalHandler.failing());

    @Test
    void aBoundSignalReachesItsHandlerWithThePayload() {
        root.bind(OPENED, heard::add);
        root.emit(OPENED, "AAPL");
        assertEquals(List.of("AAPL"), heard);
    }

    @Test
    void aSignalWithoutPayloadBindsARunnable() {
        root.bind(CLOSED, () -> heard.add("closed"));
        root.emit(CLOSED, null);
        assertEquals(List.of("closed"), heard);
    }

    @Test
    void anUnboundSignalTravelsUpToTheEnclosingScope() {
        SignalScope child = SignalScope.nested(() -> root);
        SignalScope grandchild = SignalScope.nested(() -> child);
        root.bind(OPENED, heard::add);
        grandchild.emit(OPENED, "AAPL");
        assertEquals(List.of("AAPL"), heard);
    }

    @Test
    void theNearestBindingWinsAlone() {
        SignalScope child = SignalScope.nested(() -> root);
        root.bind(OPENED, payload -> heard.add("root"));
        child.bind(OPENED, payload -> heard.add("child"));
        child.emit(OPENED, "AAPL");
        assertEquals(List.of("child"), heard);
    }

    @Test
    void theEnclosingScopeIsAskedForWhenTheSignalIsSent() {
        AtomicReference<SignalScope> enclosing = new AtomicReference<>(root);
        SignalScope child = SignalScope.nested(enclosing::get);
        SignalScope other = SignalScope.nested(() -> root);
        other.bind(OPENED, heard::add);
        enclosing.set(other);
        child.emit(OPENED, "moved");
        assertEquals(List.of("moved"), heard);
    }

    @Test
    void theRootHandsAnUnboundSignalToItsHandler() {
        var e = assertThrows(IllegalStateException.class, () -> root.emit(OPENED, "AAPL"));
        assertTrue(e.getMessage().contains("SignalScopeTest#opened"), e.getMessage());
    }

    @Test
    void aScopeBindsASignalOnce() {
        root.bind(OPENED, heard::add);
        assertThrows(IllegalStateException.class, () -> root.bind(OPENED, heard::add));
    }
}
