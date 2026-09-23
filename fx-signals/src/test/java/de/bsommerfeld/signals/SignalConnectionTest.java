package de.bsommerfeld.signals;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalConnectionTest {

    private static final SignalKey<String> OPENED = SignalKey.of(SignalConnectionTest.class, "opened");

    private final SignalConnection connection = new SignalConnection(SignalConnectionTest.class);

    @Test
    void forwardsToTheEmitterItIsConnectedTo() {
        List<Object> sent = new ArrayList<>();
        connection.connect(new SignalEmitter() {
            @Override
            public <P> void emit(SignalKey<P> key, P payload) {
                sent.add(payload);
            }
        });
        connection.emit(OPENED, "AAPL");
        assertEquals(List.of("AAPL"), sent);
    }

    @Test
    void refusesASignalBeforeItIsConnected() {
        var e = assertThrows(IllegalStateException.class, () -> connection.emit(OPENED, "AAPL"));
        assertTrue(e.getMessage().contains("constructor"), e.getMessage());
    }

    @Test
    void connectsOnce() {
        SignalScope scope = SignalScope.root(UnhandledSignalHandler.failing());
        connection.connect(scope);
        assertThrows(IllegalStateException.class, () -> connection.connect(scope));
    }
}
