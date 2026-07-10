# Was hat sich geändert?

**Das Terminal hat jetzt eine Widget-Übersicht - wie Mission Control auf dem Mac.**

Neben dem Herz in der Titelleiste sitzt ein neuer Raster-Knopf. Ein Klick zoomt aus dem Terminal heraus und legt alle Widgets als Karten vor euch hin - jede in ihren echten Proportionen, mit Icon und Namen darunter wie auf dem Mac. Die Karten lassen sich per Drag & Drop frei anordnen, das Terminal merkt sich eure Anordnung. Ein Klick auf eine Karte zoomt flüssig in das Widget hinein - Vollbild, Inhalt mittig, nichts lenkt ab. Mit Escape (oder dem Raster-Knopf) geht es genauso geschmeidig wieder zurück.

> 📸 **[Bild einfügen: Die Grid-Übersicht mit den drei Karten Schlagzeilen / Financial Juice / Fear & Greed, Namens-Pille unter jeder Karte]**

Im Vollbild schweben links am Rand runde Knöpfe mit allem, was nur dieses Widget betrifft: bei den Schlagzeilen der Filter und die Schlagzeilen-Einstellungen, bei den anderen eine kurze Erklärung. Die Panels breiten sich beim Klick direkt aus dem Knopf heraus aus.

> 📸 **[Bild einfügen: Schlagzeilen im Vollbild mit geöffnetem Filter-Panel, das aus dem schwebenden Knopf links aufgeht]**

**Und: Das Fear-&-Greed-Barometer ist jetzt ein eigenes Widget.** In der Übersicht steckt neben Schlagzeilen und Financial Juice eine ausgewachsene Version des kleinen Tachos aus der Kopfzeile: großes Barometer mit Zeiger, die fünf Stimmungszonen von Extreme Angst bis Extreme Gier, der Vergleich zu gestern - und darunter der Verlauf der letzten 12 Monate als Chart, mit Details beim Drüberfahren.

> 📸 **[Bild einfügen: Das Fear-&-Greed-Widget im Vollbild - großes Barometer mit „63", GIER-Pille, Zonen-Chips und dem 12-Monats-Chart]**

**Daneben liegt jetzt ein EUR/USD-Widget.** Der kleine Kurs aus der Financial-Juice-Kopfzeile ist zum eigenen Widget ausgewachsen: In der Übersicht eine kompakte Kachel neben dem Fear & Greed - großer Kurs zwischen EU- und US-Flagge, die Tagesveränderung in Grün oder Rot und der Kursverlauf des Tages. Im Vollbild kommen Tagesspanne, 52-Wochen-Spanne, der offizielle EZB-Referenzkurs, der Blick in die Gegenrichtung (1 USD in Euro) und ein ganzes Jahr Kursverlauf als Chart mit Details beim Drüberfahren dazu. Warum das zählt: US-Aktien, Optionen und Krypto sind in Dollar gepreist - steigt der Euro, wird euer Einstieg billiger, fällt er, teurer.

> 📸 **[Bild einfügen: Das EUR/USD-Widget im Vollbild - großer Kurs „1,1435" zwischen den Flaggen, grüne Tages-Pille, Kennzahlen-Kacheln und das 12-Monats-Chart]**

## Restliches

- Der Schlagzeilen-Filter wandert im Vollbild in die schwebenden Knöpfe links - in der klassischen Ansicht bleibt er wie gewohnt oben im Kopf.
- „Bilder mitanalysieren" und „Daten löschen" sind aus den allgemeinen Einstellungen in die schwebenden Knöpfe des Schlagzeilen-Widgets umgezogen - sie betreffen nur die Schlagzeilen, also wohnen sie jetzt auch dort.
- Das Erscheinungsbild (hell/dunkel) gilt auch in der neuen Übersicht und im Vollbild.
- Die Schlagzeilen lesen News-Artikel jetzt wirklich: Statt nur der Überschrift fließen die Kernfakten aus dem Artikel selbst in die Zeile ein - Zahlen, Kursziele, das eigentliche Ereignis. Das Lesen passiert im Hintergrund und bremst nichts aus (abschaltbar über die Einstellung "read-articles").
- Ein Fehler wurde behoben, durch den dieselbe alte Diskussion immer wieder als "neu" aufgegriffen wurde - weniger wiedergekäute Zeilen, und die KI hat spürbar mehr Luft für frische Themen.
- Kurse hängen jetzt am richtigen Papier: Bevor eine Schlagzeile einen Preis bekommt, entscheidet die KI mit allen Fakten auf dem Tisch (Typ, Kategorie, ISIN), welches Instrument der Käfig wirklich meint - gleichnamige Auslands-Zwillinge fliegen raus, statt mit dem richtigen Kurs fürs falsche Papier zu glänzen. Einmal entschieden, merkt sich das Terminal die Zuordnung dauerhaft.
- Bitcoin und andere Kryptowährungen kommen jetzt in Euro mit Kursverlauf direkt vom Handelsplatz, statt als umgerechneter Dollar-Kurs - und gelten rund um die Uhr als live, nicht nur zu Börsenzeiten.
- Die Instrument-Erkennung prüft jetzt zusätzlich den Preis: Weicht der Kurs des gewählten Papiers um Größenordnungen von der Referenz ab (ein Zertifikat statt der Aktie, eine Anleihe, ein Namensvetter), fliegt der Treffer raus - und hat die KI ein Papier einmal bewusst ausgeschlossen, sucht die Kurskette nicht doch noch per Namensraten danach.
- Sammel-Threads ("Welche Aktien haltet ihr?") fluten die Wire nicht mehr: Ein Kommentar, der viele Namen aufzählt, erzählt eine Geschichte - nicht eine pro Name. Der leise Einzel-Pick bekommt weiterhin seine eigene Zeile.
- Rote Markierungen sitzen wieder präziser: Ein Analysten-Kursziel allein macht keine Zeile mehr rot - es zählt aber mit, wenn Kurs, News und Käfig alle in dieselbe Richtung zeigen.
- Das Artikel-Lesen erkennt jetzt Cookie-Zustimmungsseiten und ähnliche Hüllen und wertet sie nicht mehr als Artikel aus - vorher landete dort nur Datenmüll statt Fakten in der Zeile.
