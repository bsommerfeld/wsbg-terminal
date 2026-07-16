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
            "QUANTITATIVE SIGNALE (im Code berechnet, nicht geschaetzt). "
                    + "Nutze sie als Prior und Kontext, zitiere die Zahl wenn du dich "
                    + "auf sie stuetzt, und widersprich ihnen ausdruecklich, wenn die "
                    + "inhaltliche Lage etwas anderes sagt - ein Signal ist ein Indiz, "
                    + "kein Urteil.";

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
