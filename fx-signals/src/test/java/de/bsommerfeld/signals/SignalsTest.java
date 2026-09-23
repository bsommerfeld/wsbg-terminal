package de.bsommerfeld.signals;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class SignalsTest {

    @Test
    void namesTheGeneratedImplementationBesideTheOwner() {
        assertEquals("de.bsommerfeld.signals.SignalsTestSignals$Emitting",
                Signals.implementationName(SignalsTest.class));
        assertEquals("de.bsommerfeld.signals.SignalsTest_NestedSignals$Emitting",
                Signals.implementationName(Nested.class));
    }

    @Test
    void aClassWithoutSignalsIsItsOwnImplementation() {
        assertSame(Nested.class, Signals.implementationOf(Nested.class));
    }

    static class Nested {
    }
}
