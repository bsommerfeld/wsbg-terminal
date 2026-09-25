# TinyUpdate

Datei-genaue Updates für Java-Anwendungen aus GitHub Releases - pro Plattform, ohne Laufzeit-Abhängigkeiten.

Zwei Hälften, ein Namensschema:

- **`action/`** - GitHub Action, die Build-Artefakte zu Release-Assets packt (Manifest + Archive).
- **`src/`** - Java-Client (`de.bsommerfeld.tinyupdate`), der diese Assets liest und die Installation abgleicht.

## Client

```java
TinyUpdateClient client = new TinyUpdateClient(
        GitHubRepository.of("bsommerfeld/wsbg-terminal"),
        Path.of(System.getProperty("user.home"), ".wsbg", "app"),
        ReleaseChannel.STABLE,
        ReleaseAssetNames.of("wsbg", Platform.current()));

if (!ConnectivityProbe.isOnline()) {
    return; // offline: installierte Version starten
}

UpdateResult result = client.update(progress ->
        System.out.printf("%s %d/%d %.0f%%%n",
                progress.phase(), progress.step(), progress.totalSteps(),
                progress.progressRatio() * 100), 0);

System.out.println(result.updated() ? "Aktualisiert auf " + result.version() : "Aktuell");
```

Nur prüfen, nichts anfassen:

```java
boolean pending = client.isUpdatePending(); // Release-JSON + Manifest + lokales Hashing
```

### Ablauf von `update()`

```
1. Release holen (Kanal entscheidet welches)
2. Kein Manifest am Release  → nichts tun (CI lädt noch hoch)
3. Manifest laden, SHA-256 gegen lokale Dateien diffen
4. Diff leer                 → version.txt nachziehen, fertig
5. app.zip laden, geänderte Dateien entpacken
6. Neu diffen; nur wenn noch etwas fehlt: deps.zip laden
7. Alle Dateien gegen das Manifest verifizieren
8. Verwaiste Dateien löschen
9. Tag in version.txt schreiben
```

- **Hashes entscheiden, nicht der Tag.** Gleicher Tag mit neuem Inhalt wird angewendet, gelöschte oder kaputte Dateien werden repariert.
- **`version.txt` erst nach erfolgreicher Verifikation.** Ein abgebrochener Lauf wird beim nächsten Start fertiggestellt.
- **Löschen zuletzt.** Schlägt etwas vorher fehl, bleibt die alte Installation vollständig.
- **Downloads ab 2 MB parallel** über bis zu 8 HTTP/1.1-Range-Verbindungen (1 pro MB); ignoriert der Server `Range`, eine Verbindung.

### Kanäle

| Kanal          | Quelle                                         |
|----------------|------------------------------------------------|
| `STABLE`       | `/releases/latest` - ohne Drafts und Pre-Releases |
| `EXPERIMENTAL` | neuester nicht-Draft aus `/releases`, Pre-Releases inklusive |

Wechsel von `EXPERIMENTAL` zurück auf `STABLE` ist ein Downgrade - gewollt, da nur Hashes verglichen werden.

### Fortschritt

`UpdateProgress(phase, step, totalSteps, progressRatio, speedBytesPerSec)` - `progressRatio` gilt pro Phase (`-1` = unbestimmt), der Schrittzähler läuft über die Downloads. `extraSteps` zählt eigene Folgeschritte des Aufrufers mit ein, `UpdateResult.downloadSteps()` sagt, wo er weiterzählt.

`phase()` ist immer ein `UpdatePhase.token()` - stabile englische Keys für die i18n des Aufrufers:
`CHECKING`, `UP_TO_DATE`, `DOWNLOADING_UPDATE`, `DOWNLOADING_DEPENDENCIES`, `EXTRACTING_FILES`, `EXTRACTING_DEPENDENCIES`, `VERIFYING_INTEGRITY`, `UPDATE_COMPLETE`.

### Lokal testen

`GitHubRepository` nimmt eine eigene API-Basis - gleicher Codepfad, anderer Host:

```java
new GitHubRepository("owner", "repo", "http://localhost:8080");
```

## Action

```yaml
- name: Pack with TinyUpdate
  id: tinyupdate
  uses: ./tinyupdate/action
  with:
    artifact-dirs: package/${{ matrix.platform }}
    scripts: 'setup.* launch.*'
    asset-prefix: wsbg
    platform: ${{ matrix.platform }}
    app-group-id: de.bsommerfeld

# Reihenfolge: Archive zuerst, Manifest zuletzt
- run: |
    gh release upload "$TAG" "${{ steps.tinyupdate.outputs.zip-path }}" --clobber
    gh release upload "$TAG" "${{ steps.tinyupdate.outputs.deps-zip-path }}" --clobber
    gh release upload "$TAG" "${{ steps.tinyupdate.outputs.app-zip-path }}" --clobber   # einmal pro Release
    gh release upload "$TAG" "${{ steps.tinyupdate.outputs.manifest-path }}" --clobber
```

Der Client ignoriert ein Release, solange das Manifest fehlt - deshalb wird es zuletzt hochgeladen.

### Inputs

| Input           | Default        | Zweck |
|-----------------|----------------|-------|
| `artifact-dirs` | -              | Komma-getrennte Verzeichnisse mit JARs → `lib/` (ohne `-sources`/`-javadoc`, dedupliziert) |
| `script-dir`    | `.script`      | Quelle für `bin/` |
| `scripts`       | `setup.*`      | Glob-Muster in `script-dir` |
| `images-dir`    | -              | PNGs → `images/` |
| `extra-dir`     | -              | Baum 1:1 ins Paket-Root, zuletzt kopiert (z. B. native Libraries) |
| `asset-prefix`  | -              | Trennt mehrere Anwendungen in einem Release |
| `platform`      | -              | `Platform.current()`-Kennung, leer = plattformneutral |
| `app-group-id`  | -              | Maven-groupId der eigenen JARs → `app.zip` |
| `output-dir`    | `updater-dist` | Zielverzeichnis |

Outputs: `manifest-path`, `zip-path`, `app-zip-path`, `deps-zip-path` (leer, wenn nicht geschrieben).

### Paket

```
lib/      JARs + extra-dir
bin/      Skripte
images/   PNGs
```

`app.zip` = `bin/`, `images/` und JARs mit `META-INF/maven/<app-group-id>/`. `deps.zip` = alles andere. `files.zip` = alles - Fallback, wenn eine Hälfte am Release fehlt.

Manifest:

```json
{
  "version": "v1.4.0",
  "files": [
    { "path": "lib/app-1.4.0.jar", "sha256": "9f2c…", "size": 482113 }
  ]
}
```

## Asset-Namen

Nicht-leere Teile aus Präfix, Plattform und Basisname, verbunden mit `-`. `app.zip` trägt nie die Plattform - alle Plattformen teilen es. Regel identisch in `ReleaseAssetNames` und `action.yml`.

| `ReleaseAssetNames.of(…)`       | Manifest                          | Voll                            | App            | Deps                            |
|---------------------------------|-----------------------------------|---------------------------------|----------------|---------------------------------|
| `("", "")`                      | `update.json`                     | `files.zip`                     | `app.zip`      | `deps.zip`                      |
| `("wsbg", "windows-x86_64")`    | `wsbg-windows-x86_64-update.json` | `wsbg-windows-x86_64-files.zip` | `wsbg-app.zip` | `wsbg-windows-x86_64-deps.zip`  |

Plattformen: `{macos,windows,linux}-{x86_64,aarch64}`.

## Build

```bash
mvn -pl tinyupdate test
```

Java-Modul `de.bsommerfeld.tinyupdate`, benötigt nur `java.net.http`. Die Action braucht bash 4 (GitHub-Runner: ja, macOS-Standard: nein).
