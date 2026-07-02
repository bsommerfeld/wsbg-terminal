# Network Traffic Blending

Stand: 2026-07-02

Ziel: Bei allen externen Quellen (Reddit, Yahoo, Lang & Schwarz, wallstreet-online,
FearGreed, EUR/USD, ...) so wie normaler Browser-Traffic auftreten, **ohne
Funktionsverlust und ohne Degradierung** der Datenqualitaet. "Blenden" heisst hier
nicht Unsichtbarkeit, sondern **keine Bot-Heuristik triggern**: die Kombination aus
Residential-IP + echtem Browser-Fingerprint + natuerlichem Timing + geringem/
konditionalem Volumen soll uns unauffaellig halten.

Alle Massnahmen sind rein **additiv** und sitzen am zentralen Transport-Seam
(`source.net.WebFetcher`) bzw. an den Scheduler-Loops - kein per-Source-Glue,
konsistent mit der Architektur-Philosophie des Projekts.

---

## Was bereits geloest ist (nicht anfassen)

- **Echter Browser-Fingerprint (groesster Hebel):** Der `CefWebFetcher` /
  `CefFetchClient` fuehrt same-origin `fetch()` aus einem echten Chromium aus -
  realer TLS/JA3-Fingerprint, HTTP/2-Frame-Reihenfolge, Cookies, Session. Er
  fuehrt die zentrale `WebFetchChain` (`browser -> direct`) an, verdrahtet in
  `AppModule.provideWebFetcher`.
- **Alle Produktions-Clients reiten den Seam:** `LangSchwarzClient`,
  `WallstreetOnlineClient`, `WsoNewsClient`, `YahooFinanceClient`, `EurUsdClient`,
  `FearGreedClient` (und die Reddit-Pfade) bekommen den geteilten `WebFetcher`
  per `@Inject`. Die bare-`DirectWebFetcher`-Konstruktoren sind nur Test/CLI.
  -> Jede Anfrage traegt bereits den Browser-Fingerprint, sobald der Joker fuehrt.
- **Residential-IP durch lokale Desktop-Deployment:** das eine, was Scraper am
  schwersten faelschen - kostet uns nichts und ist unser staerkstes Ass.
- **Rate-Limiter pro Quelle** (`TokenBucketRateLimiter`) lesen `x-ratelimit-*`
  bereits aus.

Konsequenz: Die Fingerprint-Ebene ist faktisch fertig. Was fehlt, ist
**Timing-Natuerlichkeit** und **Volumen-Reduktion** - genau die Signale, die
auch der perfekteste Fingerprint nicht kaschiert.

---

## Die verbleibenden Hebel (priorisiert nach Wirkung/Aufwand)

### 1. Cadence jittern + Quellen staffeln  (hohe Wirkung, kleiner Aufwand)

**Problem:** Perfekt periodisches Polling ist das deutlichste Bot-Signal. Heute
laufen die Loops als `scheduleAtFixedRate` mit fixem Intervall und starten teils
zeitgleich, d.h. jede Minute feuert ein exakt gleicher Burst:

- `PassiveMonitorService.scanCycle` - `scheduleAtFixedRate(..., 30, updateIntervalSeconds, SECONDS)`
- `EurUsdMonitorService`, `FearGreedMonitorService`, `FnMonitorService`
- `FjNewsPublisher`, `MarketHoursPublisher` (UI-Publisher; niedrigere Prioritaet,
  da teils rein lokal)

**Loesung:** Ein **jitterndes Reschedule** statt fixem Takt. Pro Zyklus mit
`baseInterval * (1 +/- jitter)` neu planen (self-rescheduling `schedule(...)`
statt `scheduleAtFixedRate`), plus ein zufaelliger **Initial-Offset** pro Monitor,
damit die Quellen nicht synchron feuern. Zentral als kleiner Helfer in `core`
(z.B. `JitteredScheduler`), den alle Monitore nutzen.

- Kein Funktionsverlust: Frische aendert sich im Mittel nicht, nur der exakte
  Zeitpunkt streut.
- Konfigurierbar (Default an), damit der Jitter-Anteil ohne Rebuild justierbar ist.

### 2. Conditional Requests (ETag / If-Modified-Since)  (hohe Wirkung, mittlerer Aufwand)

**Problem:** Wir holen bei jedem Poll den vollen Body, auch wenn sich nichts
geaendert hat. Das ist Volumen, das gegen Reddits **per-IP-Volumenlimit** zaehlt -
das einzige Limit, gegen das kein Fingerprint-Trick hilft.

**Loesung:** Ein **`CachingWebFetcher`-Decorator**, der die `WebFetchChain`
umschliesst (verdrahtet in `AppModule.provideWebFetcher`, *ausserhalb* der Chain,
damit er die finale Antwort sieht):

- Speichert pro URL die Validatoren (`ETag`, `Last-Modified`) + Body, bounded/LRU.
- Injiziert bei der naechsten Anfrage `If-None-Match` / `If-Modified-Since`.
- Auf `304 Not Modified`: liefert den gecachten Body zurueck (Caller sieht
  unveraendert einen 2xx mit Body).
- Ein `304` sieht aus wie eine normale Browser-Cache-Revalidierung - **senkt
  Volumen UND wirkt natuerlicher**, plus schnellere Antwort.

**Wichtige Implementierungs-Subtilitaeten (dokumentieren, nicht wegabstrahieren):**

- **Graceful degradation ist Pflicht:** Der Browser-Transport (`CefFetchClient`)
  setzt eigene Header und leitet ggf. keine caller-Request-Header in sein
  injiziertes `fetch()` weiter (siehe `WebFetcher`-Contract: "browser ignores
  session-controlled headers"). Wenn `If-None-Match` dort nicht ankommt, kommt
  eben ein normaler `200` mit vollem Body zurueck - korrekt, nur ohne Ersparnis.
  Zuerst pruefen, ob `CefFetchClient` beliebige Request-Header forwarded; wenn
  nicht, ist das ein sauberer Fallback, kein Bug.
- **Nur idempotente GETs cachen.** Die WSO-Suche/RPC ist teils POST-artig - nicht
  cachen. Fehlerantworten (>=400) nie cachen.
- **Cache bounded halten** (LRU + max Eintraege), rein in-memory (passt zur
  "Reddit-Daten nur zur Laufzeit"-Doktrin).

### 3. Proaktiver Rate-Limit-Backoff  (Follow-up, quellen-spezifisch)

**Problem:** Heute reagieren wir erst auf `429`. Ein Client, der nie ans Limit
stoesst, faellt gar nicht erst auf.

**Loesung:** `x-ratelimit-remaining` / `x-ratelimit-reset` aus der Antwort in den
`TokenBucketRateLimiter` zurueckspeisen, sodass er **vorausschauend** drosselt,
wenn das Restkontingent knapp wird. Niedrigere Prioritaet, da quellen-spezifischer
und weniger additiv als 1+2.

---

## Ehrliche Einschraenkung (nicht ueberversprechen)

- "Wie normaler Traffic aussehen" != unsichtbar. Es gibt eine echte Spannung
  zwischen "menschlich wirken" (bursty, unregelmaessig) und "alle 60 s frisch
  pollen". Realistisches Ziel: keine Heuristik triggern.
- Gegen ein reines **Volumen-Limit pro IP** hilft am Ende nur weniger/konditionales
  Volumen (Hebel 2), kein Tarn-Trick.
- Residential-IP + echter Fingerprint (haben wir) + Jitter + Conditional Requests
  bringen ~90 %. Mehr braucht es fuer eine lokale Single-User-App nicht.

---

## Reconcile-Request (fuer Fable 5 zum Umsetzen)

> Formuliert als Soll/Ist-Abgleich. Ein Agent liest den Ist-Zustand, stellt den
> Soll-Zustand her, verifiziert gegen die Akzeptanzkriterien. Umfang bewusst auf
> Hebel 1 + 2 begrenzt (die additiven High-Leverage-Wins); Hebel 3 ist Follow-up.

**Kontext zuerst lesen:** dieses Dokument komplett, dann
`source-api/.../source/net/{WebFetcher,WebFetchChain,DirectWebFetcher,WebResponse}.java`,
`AppModule.provideWebFetcher`, `PassiveMonitorService` (Scheduling-Block um Zeile
133), `terminal/.../ui/net/CefFetchClient.java` (ob Request-Header forwarded
werden).

### Soll-Zustand

1. **Jitter + Stagger (Hebel 1):**
   - Ein kleiner, wiederverwendbarer Helfer in `core` (Vorschlag:
     `JitteredScheduler`), der einen Task self-rescheduling mit
     `baseInterval * (1 +/- jitterPercent/100)` plant und einen zufaelligen
     Initial-Offset im Intervall `[0, baseInterval)` setzt.
   - Umgestellt werden mindestens die **externen** Poll-Loops:
     `PassiveMonitorService.scanCycle`, `EurUsdMonitorService`,
     `FearGreedMonitorService`, `FnMonitorService`. (UI-only-Publisher wie
     `MarketHoursPublisher` sind optional - nur wenn sie extern fetchen.)
   - Ein Config-Key steuert den Jitter-Anteil (Vorschlag: `net.poll-jitter-percent`,
     Default `20`, `0` = aus/altes Verhalten). Bei `0` faellt es auf einen
     regulaeren Fixed-Delay zurueck.

2. **Conditional Requests (Hebel 2):**
   - Ein `CachingWebFetcher` (im `source-api`-Modul), der einen delegierenden
     `WebFetcher` umschliesst und in `AppModule.provideWebFetcher` **um die
     `WebFetchChain` herum** verdrahtet wird.
   - Verhalten: per URL `ETag`/`Last-Modified` + Body merken (bounded LRU);
     `If-None-Match`/`If-Modified-Since` injizieren; auf `304` den gecachten Body
     als `200`-aequivalente `WebResponse` zurueckgeben.
   - Nur GET/idempotent cachen; `>=400` nie cachen; wenn der Downstream-Transport
     den Conditional-Header verschluckt, sauber auf normales `200`-Fetch
     zurueckfallen (kein Fehler).
   - Optionaler Config-Key zum Abschalten (Vorschlag: `net.conditional-requests`,
     Default `true`).

### Ist-Zustand (UMGESETZT 2026-07-02)

- Alle Prod-Clients injizieren bereits den geteilten `WebFetcher` (Chain
  `browser -> direct`) -> Hebel "alles ueber den Browser routen" ist bereits
  erledigt, **nicht** erneut umsetzen.
- **Hebel 1 LIVE:** `core/util/JitteredScheduler` (self-rescheduling,
  `base * (1 +/- jitter)`), umgestellt: `PassiveMonitorService.scanCycle`,
  `EurUsdMonitorService`, `FearGreedMonitorService`, `FnMonitorService`
  (standalone-Modul: eigener `pollJitterPercent` in `FinanznachrichtenConfig`,
  Default 20). Config-Key `net.poll-jitter-percent` (Default 20, `0` = exakt
  altes Verhalten) in der neuen `NetConfig`-Sektion.
  **Bewusste Abweichung vom Vorschlag:** der zufaellige `[0, base)`-Offset wird
  als **Phasen-Offset nach dem ERSTEN Lauf** eingeschoben, nicht vor ihm - der
  erste Fetch behaelt das initialDelay des Callers (EUR/USD und Fear&Greed
  fetchen absichtlich bei t=0 fuer den schnellen First Paint; ein
  Voll-Intervall-Offset davor waere Funktionsverlust). Desynchronisierung ist
  ab dem zweiten Zyklus voll wirksam; der Boot-Burst selbst sieht aus wie ein
  Page-Load und geht pro Host ohnehin an verschiedene Hosts.
- **Hebel 2 LIVE:** `source-api/net/CachingWebFetcher` umschliesst die Chain in
  `AppModule.provideWebFetcher` (ausserhalb, sieht die finale Antwort).
  ETag/Last-Modified + Body pro URL (LRU, bounded: 256 Eintraege UND ~32 MB
  Body-Summe, Einzel-Body-Cap), `304` -> gecachter Body als urspruengliches
  `200`. Nur 2xx MIT Validator werden gecached (dynamische Endpoints ohne
  Validator passieren untouched); `>=400` nie; ein transienter Fehler evicted
  keine bekannten Validatoren. Config-Key `net.conditional-requests`
  (Default `true`).
- **Header-Forwarding-Frage geklaert:** `CefFetchClient.buildScript` setzt die
  fetch()-Header hart (`Accept` only) - Caller-Header (also auch
  `If-None-Match`) erreichen den Browser-Transport NICHT. Ergebnis wie
  dokumentiert: normales `200`, Cache wird refresht, nur die Ersparnis entfaellt
  auf dem Browser-Pfad. Follow-up-Option: Conditional-Header ins injizierte
  `fetch()` durchreichen (same-origin erlaubt sie), dann greift Hebel 2 auch
  browser-led (= auf dem Reddit-Hauptpfad).

### Akzeptanzkriterien

- [x] Externe Poll-Loops feuern nicht mehr synchron und nicht mehr exakt
      periodisch (Jitter sichtbar in Logs / im Timing). *(per Unit-Test
      nachgewiesen: Delay-Grenzen + Phasen-Offset; Start-Logs nennen den
      Jitter-Anteil)*
- [x] Ein unveraenderter Endpoint liefert beim zweiten Poll ein `304` und wird
      aus dem Cache bedient (per Log/Test nachweisbar); der Caller sieht
      unveraenderte, korrekte Daten. *(CachingWebFetcherTest: 304-Roundtrip;
      Debug-Log `304 revalidation for ...`)*
- [x] Kein Funktionsverlust: Headlines/Preise/News erscheinen unveraendert;
      bei `jitter=0` bzw. `conditional=false` exakt altes Verhalten.
      *(jitter=0: kein Offset, Delay exakt base; conditional=false: nackte
      Chain wie zuvor. First-Paint-Ticks bei t=0 unveraendert, s.o.)*
- [x] Graceful degradation: verschluckt der Browser-Transport den
      Conditional-Header, kommt ein normaler `200` - kein Fehler, keine Luecke.
      *(Test `degradesGracefullyWhenTransportIgnoresConditionalHeaders`)*
- [x] `mvn clean install -DskipTests` gruen; Unit-Tests fuer `JitteredScheduler`
      (Intervall-Grenzen, offset in range) und `CachingWebFetcher`
      (304-Roundtrip, No-Cache bei >=400, Bound-Enforcement). *(7 + 8 Tests
      gruen; Modul-Tests core/source-api/currency/fear-greed/finanznachrichten/
      agent alle gruen)*

### Constraints

- **Keine neuen Abhaengigkeiten**, kein Over-Engineering: kleine, fokussierte
  Klassen am bestehenden Seam. Kein per-Source-Glue.
- **Gedankenstrich = immer `-`** (nie `—`/`–`) in jedem erzeugten Text.
- Neue **user-sichtbare** Strings (falls ein Settings-Toggle dazukommt) muessen
  durch die Front-end-i18n-Schicht (`web/js/i18n/i18n.js`); reine Config-Keys +
  Log-Zeilen sind nicht user-facing und bleiben hardcoded.
- Nur `Math`/JDK-Zufall in Produktionscode ist ok (dies ist Java, kein
  Workflow-Skript).
- Hebel 3 (proaktiver Rate-Limit-Backoff) ist **out of scope** dieses Requests -
  als eigenen Follow-up fuehren.

### Out of scope

- Fingerprint-/Header-Spoofing (bereits durch den Browser-Joker geloest).
- Proxy/IP-Rotation (Residential-IP ist bereits ideal; nicht noetig).
- Persistente Caches auf Platte (widerspraeche der In-Memory-Doktrin fuer
  Reddit-Daten).
