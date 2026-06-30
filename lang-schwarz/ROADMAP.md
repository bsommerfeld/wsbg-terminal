# Lang & Schwarz — die universelle EUR-Quelle (Roadmap)

> Stand: 2026-06-30. Lebende Notiz. Was L&S kann, was wir heute davon nutzen, und
> wohin die Reise geht.

## Warum L&S

L&S (Trade Republics Handelsplatz) ist **die Börse, an der unsere Affen wirklich
handeln**. Preise kommen in **EUR**, mit **Sparkline**, ISIN-exakt. Yahoo liefert
USD-Linien fremder Notierungen — für dieses Publikum das falsche Produkt.

**Die Erkenntnis (2026-06-30): wir nutzen erst die Spitze des Eisbergs.** L&S
führt nicht nur Aktien — sondern **ETFs, Fonds, Anleihen, Währungen (Krypto),
Rohstoffe und Derivate**. Alles in EUR. Die L&S-Suche liefert den Asset-Typ
**direkt mit** (`categorySymbol`, z. B. `AKTIE` / `ETF` / `WÄHRUNG` / `ROHSTOFF` /
…) — das ist der Hebel zur Disambiguierung.

## Was wir HEUTE nutzen

- **Aktien + ETFs** → L&S per Name (mit WSO→ISIN-Fallback für Fuzzy-Fälle).
  Preiskette: `FallbackPriceSource` (terminal-Modul), L&S-Name-first seit 2026-06-30.
- Alles andere (Index/Krypto/FX/Rohstoff) → **Yahoo** (`^GDAXI`, `BTC-USD`,
  `GC=F`, `=X`). Index in Punkten, Krypto/Rohstoff in USD.

## Die Vision

**L&S wird die universelle EUR-Preisquelle. Yahoo wird zur Typ-Weiche + News-Quelle.**

```
pro Subjekt, parallel:
  • L&S-Name  → Identität + EUR-Preis + Sparkline   (fast exit, kein WSO)
  • Yahoo     → News (firmenbezogen) + Quote-Typ
entscheiden anhand Yahoos Typ:
  crypto/index/FX/Rohstoff → die passende L&S-Notiz (categorySymbol WÄHRUNG/ROHSTOFF)
                             bzw. Yahoo, wo L&S nichts hat
  Aktie/ETF                → L&S AKTIE/ETF (z. B. MUV2.DE statt Yahoos 1MUV2.MI)
News hängt an der FIRMA, nie an der ISIN — getrennt von der Preis-/ISIN-Auflösung.
```

**Die Disambiguierung ist der Knackpunkt, nicht die Abdeckung.** L&S *hat* fast
alles — aber eine Namenssuche „Bitcoin" liefert zuerst **Bitcoin Group SE** (Aktie,
`ADE.DE`), nicht die Währung (`de/waehrung/bitcoin-btc`). Ein Krypto-**Katalog**
wäre Wahnsinn (Memecoins für alles). Stattdessen: **Yahoos Quote-Typ ist der
Router** — sagt Yahoo „crypto", nehmen wir die L&S-`WÄHRUNG`-Notiz, nie die Aktie.
Yahoo läuft eh für News, der Typ ist also gratis.

## Roadmap-Achsen

1. **Rohstoffe → L&S `ROHSTOFF`.** Gold/Silber in EUR + Sparkline statt Yahoos
   USD-Future (`GC=F`). L&S featured sie zuverlässig.
2. **Krypto → L&S `WÄHRUNG`.** Bitcoin & Co. in EUR + Sparkline, geroutet über
   Yahoos `crypto`-Typ (hält „Bitcoin Group SE" draußen).
3. **Derivate — das Killer-Feature, das Yahoo NIE kann: den exakten Schein bepreisen.**
   - **Mit WKN/ISIN im Post** → `LangSchwarzClient.resolveByIsin` → **exakt der
     Schein** (Emittent/Strike/Laufzeit), echter Kurs + Hebel. Identifier
     **deterministisch per Regex** ziehen (WKN = 6 Zeichen, ISIN = 12), kein LLM.
   - **Multiplikator Vision:** Affen posten Screenshots ihrer Derivate-Positionen
     mit WKN + Hebel. **Vision-OCR → WKN → L&S → exakter Scheinpreis.** Bild rein,
     exakter Kurs raus.
   - Ohne Identifier (`„Hebel 5 auf Tesla"`) ist der Schein unbestimmbar
     (tausende Treffer) → nur der Basiswert (Tesla) wird bepreist.
4. **Fonds / Anleihen** — `categorySymbol FONDS` / Anleihe, analog, niedrigere
   Priorität (selten Wire-Thema).

## Offene Fragen / zu verifizieren

- Liefert die L&S-**Quote**-API (chart) für `WÄHRUNG`/`ROHSTOFF`/Derivat dieselbe
  Struktur wie für Aktien? (Parsing in `LangSchwarzClient` muss das abdecken.)
- Derivate-Suche: greift `resolveByIsin`/Suche auch auf WKN, oder nur ISIN?
- `categorySymbol`-Werte vollständig erfassen (bisher bestätigt: `ETF`; Rest aus
  Live-Antworten ableiten).

## Heutiger Client-Stand (Bezug)

`LangSchwarzClient` (Modul `lang-schwarz`): Suche → Instrument (ISIN +
`categorySymbol`) + Chart → EUR-`MarketSnapshot`. Filtert aktuell auf
Aktie/ETF/Fonds; `resolveByIsin` ist ISIN-exakt (kein Namens-Fuzz). Die Erweiterung
auf Währung/Rohstoff/Derivat ist additiv — der `categorySymbol` steht schon in den
Suchergebnissen.
