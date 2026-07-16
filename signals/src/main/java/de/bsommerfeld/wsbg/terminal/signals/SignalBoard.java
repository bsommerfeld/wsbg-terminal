package de.bsommerfeld.wsbg.terminal.signals;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Buendelt mehrere {@link SignalReading}s zu einem fertigen Kontext-Block.
 * Der Block ist die Uebergabestelle an die Redaktion: oben die stehende
 * Lese-Regel (einmal, nicht pro Signal), darunter eine Zeile pro Befund.
 */
public final class SignalBoard {

    private static final String HEADER =
            "QUANT SIGNALS (house-computed in code, never guessed). Use them as "
                    + "priors and context, cite the number when you lean on it, and "
                    + "contradict them EXPLICITLY when the material says otherwise - "
                    + "a signal is a clue, never a verdict.";

    private SignalBoard() {
    }

    /** Rendert die Befunde als injizierbaren Kontext-Block; leere Liste ergibt den leeren String. */
    public static String render(List<SignalReading> readings) {
        if (readings == null || readings.isEmpty()) {
            return "";
        }
        return HEADER + "\n" + readings.stream()
                .map(SignalReading::toContextLine)
                .collect(Collectors.joining("\n"));
    }
}
