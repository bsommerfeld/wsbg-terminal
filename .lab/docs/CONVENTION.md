# CONVENTION — `.lab/docs/`

Vorschrift für alle Dokumente in diesem Ordner. Diese Datei (`CONVENTION.md`) ist
die einzige mit festem Namen; sie selbst folgt der Regel **nicht**.

## Zweck

`.lab/docs/` ist das Gedächtnis der Arbeit an der Editorial-Pipeline und am
`.lab`-Harness: Entscheidungen, Roadmap, Befunde aus Test-Läufen, offene
Baustellen. Es ist ein **Verlauf**, kein lebendes Dokument — alte Einträge
bleiben stehen, Neues kommt als neue Datei dazu.

## Regeln

1. **Dateiname = aktuelles Datum + Uhrzeit.** Format strikt:
   `YYYY-MM-DD_HH-MM.md` (z. B. `2026-06-05_02-37.md`). Sortierbar, eindeutig.
2. **Niemals überschreiben.** Jede Arbeitssitzung / jeder Entscheidungsstand ist
   eine **neue** datierte Datei. Korrekturen referenzieren die ältere Datei,
   ersetzen sie nicht.
3. **Format: Markdown (`.md`).**
4. **Klar strukturiert:** Titel/Überschriften (`#`, `##`), Klartext, kurze
   Absätze. Keine Code-Dumps, keine Bildschirm-Logs in Rohform — verdichten.
5. **Status sichtbar machen.** Einheitliche Marker:
   - ✅ erledigt
   - 🔧 in Arbeit
   - 🟡 entschieden, noch nicht gebaut
   - ⏸️ aufgeschoben / nur dokumentiert
   - ❓ offen / Entscheidung nötig

## Empfohlene Gliederung eines Eintrags

```
# <Titel> — <Datum HH:MM>

## Kontext
Worum ging es in dieser Sitzung.

## Entscheidungen
Was wurde festgelegt (+ kurze Begründung).

## Baustellen / Status
Liste mit Status-Markern.

## Offen / als Nächstes
Was beim nächsten Mal ansteht.
```
