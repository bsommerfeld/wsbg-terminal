package de.bsommerfeld.wsbg.terminal.signals;

import java.util.Objects;

/**
 * Ein einzelner Signal-Befund: die berechnete Kennzahl zusammen mit ihrer
 * Definition und einer konkreten Deutungsanweisung fuer das in-App-Modell.
 *
 * <p>Der Kontrakt des Hauses gilt hier woertlich: die Statistik rechnet der
 * Code, das Modell interpretiert nur. Deshalb traegt jeder Befund den
 * fertigen Kontext-Satz bereits in sich - {@link #toContextLine()} ist das,
 * was in einen Prompt injiziert wird, ohne dass das Modell die Mathematik
 * kennen muss.
 *
 * @param id             stabiler Maschinen-Schluessel, z.B. {@code "attention-entropy"}
 * @param title          kurzer deutscher Titel, z.B. {@code "Aufmerksamkeits-Entropie"}
 * @param value          roher Zahlenwert (fuer Kombinations-Signale und Schwellen im Code)
 * @param formattedValue anzeigefertige Zahl inkl. Einheit/Skala, z.B. {@code "0.42 (Skala 0-1)"}
 * @param definition     EIN deutscher Satz: was diese Zahl misst
 * @param interpretation deutsche Anweisung, wie GENAU DIESER Wert zu lesen ist
 *                       (wertabhaengig formuliert, inkl. Vorsichts-Hinweis bei duenner Datenlage)
 */
public record SignalReading(
        String id,
        String title,
        double value,
        String formattedValue,
        String definition,
        String interpretation) {

    public SignalReading {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(formattedValue, "formattedValue");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(interpretation, "interpretation");
    }

    /** Die eine Zeile, die als Kontext in einen Prompt injiziert wird. */
    public String toContextLine() {
        return "SIGNAL [" + title + "] = " + formattedValue
                + " | Was das misst: " + definition
                + " | Deutung: " + interpretation;
    }
}
