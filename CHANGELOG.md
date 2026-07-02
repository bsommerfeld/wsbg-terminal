# Was hat sich geändert?

Die KI hat bisher vor jeder einzelnen Schlagzeile heimlich seitenweise vor sich hin gegrübelt - gelesen hat das nie jemand, gekostet hat es fast die ganze Rechenzeit. Damit ist Schluss: Schlagzeilen entstehen jetzt **bis zu zehnmal schneller**, die Wire kommt auch bei viel Betrieb im Käfig nicht mehr ins Stocken, und nebenbei verschluckt die KI deutlich seltener eine Zeile oder liefert eine leere Antwort.

Die neue Geschwindigkeit trifft auf mehr Ordnung: **Ein Ereignis ist jetzt genau EINE Schlagzeile.** Bisher konnte ein einziger Thread drei fast gleiche Zeilen erzeugen - eine pro erwähntem Namen; jetzt erkennt die KI das Hauptthema des Threads und webt den Rest als Kontext in dieselbe Zeile ein. Und damit man sofort sieht, worum es geht, leuchtet der Name des Werts in jeder Schlagzeile golden auf.

> 📸 **[Bild einfügen: Schlagzeilen-Widget mit einer Zeile, in der der Firmenname golden hervorgehoben ist - z. B. „Rheinmetall" in Gold mitten im Satz]**

Und wenn eine deutsche Nebenwerte-Aktie abgeht, weiß die Wire jetzt auch warum: Neben Yahoo liest sie ab sofort auch deutsche Börsennachrichten mit. Wo bisher „massiver Kursanstieg ohne klaren Katalysator" stand, steht jetzt der echte Auslöser - konkret in die Zeile eingewoben, mit Ereignis und Akteuren statt trockenem „basierend auf Nachrichten".

---

Sobald eine neue Version verfügbar ist, leuchtet nun ein grüner Update Knopf in der Titelleiste auf.

<img width="1304" height="112" alt="Screenshot 2026-07-01 at 20 40 13" src="https://github.com/user-attachments/assets/e543055e-579b-47bb-a6fa-2dc846cb5045" />

---

Die Anzeigesprache lässt sich jetzt live umschalten.

<img width="1200" height="800" alt="ezgif-1625caa2f4a128da" src="https://github.com/user-attachments/assets/007a6208-e875-426b-8c1d-5c20ce20d61d" />


## Restliches

- Schlagzeilen lesen sich straffer: kein „Die Diskussion dreht sich um ..."-Vorgeplänkel und kein „-Update:"-Etikett mehr - jede Zeile steigt direkt mit dem Wert oder dem Ereignis ein, Fortsetzungen erzählen einfach weiter.
- Die KI sieht jetzt den mehrtägigen Kursverlauf (5 Tage, 1 Monat, Abstand zum 52-Wochen-Hoch): Ein tagelanger Lauf mit heutiger Korrektur wird als genau das erzählt - und keine Zeile behauptet mehr „steigt", wenn der Kurs an dem Tag fällt.
- Dieselbe Nachricht wird nicht mehr mehrfach zur Schlagzeile derselben Aktie: einmal konkret eingewoben, orientiert sich die nächste Zeile an dem, was schon erzählt ist.
- Rote Schlagzeilen sind jetzt verlässlicher: Die KI muss den konkreten Auslöser benennen (Runner, Squeeze, harter Katalysator, gepoolter Call ...), sonst bleibt die Zeile normal - Rot heißt wieder wirklich „hier ist was zu holen".
- Die Installation zeigt jetzt per Punkten, wie viele KI-Modelle geladen werden, und der Browser-Runtime Step friert nicht mehr ein.
- Ganz unten in den Einstellungen gibt es jetzt einen „Deinstallieren"-Knopf: ein Klick entfernt das Terminal restlos vom Rechner - App, KI-Modelle, Cache, Einstellungen und Archiv, auf jedem Betriebssystem. 

#### Microsoft Short (Windows)
- Nach der Installation startet das Terminal jetzt von selbst.
- Die ScrollBar in den Einstellungen ist nun kein dicker Klumpen mehr.
- JCEF Hintergrundprozesse werden jetzt sauber aufgeräumt.
- CURL 35 Fehler bei der Installation werden jetzt sauber abgefangen [#3](https://github.com/bsommerfeld/wsbg-terminal/issues/3)
