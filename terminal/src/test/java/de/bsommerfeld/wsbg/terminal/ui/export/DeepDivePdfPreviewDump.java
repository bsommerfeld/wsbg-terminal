package de.bsommerfeld.wsbg.terminal.ui.export;

import de.bsommerfeld.wsbg.terminal.db.DeepDiveRecord;
import de.bsommerfeld.wsbg.terminal.db.DeepDiveRecord.ChartFigure;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dev-only dump: writes the EXACT print page {@link DeepDivePdfExporter} feeds
 * to Chromium, so the paper can be printed and eyeballed outside the app.
 *
 * <p>{@code DD_PDF_PREVIEW} names the output HTML; {@code DD_PDF_FIGURES}
 * optionally names an HTML file to lift {@code <svg>} figures from (the
 * DeepDiveChartsPreviewDump output), so the page carries real pictures rather
 * than prose alone.
 */
@Tag("integration")
class DeepDivePdfPreviewDump {

    private static final Pattern SVG = Pattern.compile("<svg .*?</svg>", Pattern.DOTALL);

    @Test
    void dump() throws Exception {
        String out = System.getenv("DD_PDF_PREVIEW");
        if (out == null) return;
        Files.writeString(Path.of(out), DeepDivePdfExporter.buildHtml(record(), true));
    }

    private static DeepDiveRecord record() throws Exception {
        return new DeepDiveRecord("dd-preview", "Rheinmetall", "Rheinmetall AG", "RHM",
                "DE0007030009", 1_754_200_000L, report(), 992.10, "EUR", 42, 17, 61_000,
                figures(), List.of());
    }

    private static List<ChartFigure> figures() throws Exception {
        String src = System.getenv("DD_PDF_FIGURES");
        if (src == null) return List.of();
        Matcher m = SVG.matcher(Files.readString(Path.of(src)));
        List<ChartFigure> out = new ArrayList<>();
        int i = 0;
        // Spread the lifted figures over the report's sections in order.
        while (m.find()) {
            out.add(new ChartFigure(Math.min(i / 3, 5), "Abbildungstitel " + (i + 1),
                    "Quelle", m.group()));
            i++;
        }
        return out;
    }

    /** Prose with the shapes the typography has to carry: sections, a lead, a table. */
    private static String report() {
        return """
                ## Worum es geht
                Der Bericht prüft, was ein Anleger über das Unternehmen wissen muss, bevor er eine Position eingeht. Die Prosa trägt die Deutung, die Abbildungen tragen die Zahlenreihen, und beide stehen im selben Dokument nebeneinander.

                Ein zweiter Absatz setzt den Rhythmus fort und zeigt, wie der Zeilenfall über mehrere Zeilen läuft, damit man den Satzspiegel und die Laufweite tatsächlich beurteilen kann statt sie zu erraten.

                ## These
                Die These steht als eigener Abschnitt, damit sie im Dokument auffindbar bleibt und nicht in der Lage untergeht. **Fettung** hebt einen Begriff heraus, ohne die Zeile zu sprengen.

                ## Lage
                Die Lage beschreibt den Zustand am Stichtag. Sie verweist auf Abbildung A1 und Abbildung A2, und diese Verweise müssen im Dokument als Verweise lesbar bleiben.

                | Kennzahl | 2023 | 2024 |
                | --- | ---: | ---: |
                | Umsatz | 7,2 Mrd. € | 9,8 Mrd. € |
                | Nettogewinn | 586,0 Mio. € | 936,0 Mio. € |
                | EBIT-Marge | 11,4 % | 12,0 % |

                ## Fundamentale Entwicklung
                Die fundamentale Entwicklung trägt die Geschäftsjahresreihen. Der Abschnitt bleibt bewusst prosaisch, weil die Reihen selbst in den Abbildungen stehen und dort präziser abzulesen sind als in jedem Satz.

                ## Bewertung und Wettbewerb
                Bewertung und Wettbewerb ordnen das Papier in sein Feld ein. Auch hier gilt: die Zahlen stehen in der Abbildung, der Satz trägt die Deutung.

                ## Katalysatoren und Risiken
                Katalysatoren und Risiken benennen, was die These kippen könnte. Das ist der Abschnitt, den ein Leser zuerst sucht, wenn er skeptisch ist.

                ## Ausblick
                Der Ausblick ist datiert und verankert. Er nennt Termine, keine Stimmungen.

                ## Der Raum
                - [1] Quellenzeile eins, die als Fußnote gesetzt wird und über mehr als eine Zeile laufen kann, damit der hängende Einzug sichtbar wird.
                - [2] Quellenzeile zwei.
                """;
    }
}
