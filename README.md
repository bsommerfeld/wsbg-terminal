# WSBG Terminal 🚀🌕

## Das Bloomberg-Terminal für Arme

Willkommen im **WSBG Terminal** – dem einzigen Tool, das ihr braucht, bevor ihr eure Studienkredite in 200er Hebel auf NVIDIA yolo’t. 

Seien wir ehrlich: Keiner von uns kann sich das echte Bloomberg-Terminal leisten (das Abo kostet mehr als unser Portfolio wert ist). Deswegen haben wir dieses Ding gebaut. Es sieht professionell aus, blinkt wichtig und hilft euch dabei, euer Geld noch effizienter zu verbrennen.

> [!TIP]
> Wir wissen, dass keiner von euch Lust hat, echte DD zu lesen. Texte sind für Boomer. Wir wollen Headlines, Ticker und KI, die uns bestätigt, dass der Döner zum Mittag sicher ist.

### 🦍 Also, was kann das Ding?

Das WSBG Terminal ist eure Kommandozentrale für [**r/wallstreetbetsGER**](https://www.reddit.com/r/wallstreetbetsGER/). Statt alle 2 Sekunden F5 zu hämmern und euch durch tausende Kommentare voller Degeneration zu wühlen, liefert euch das Terminal die Fakten.

| Feature              | Beschreibung                                                                                                                                                   |
|:---------------------|:---------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Auto-Fetch**       | Der Algo zieht sich die neusten Threads automatisch rein. Nie wieder F5 drücken, während der Kurs fällt.                                                       |
| **KI-Headlines**     | Das Terminal fasst das Affengeschrei zusammen, damit ihr wisst, ob Long oder Short.                                                                            |
| **Echtzeit-Kurse**   | Live-Kurs direkt an der Schlagzeile. Tagsüber von Lang & Schwarz, nachts die **US-Nachbörse bis 2 Uhr** (falls Micron mal wieder +600 % nach Earnings rennt)   |
| **News-Ergänzungen** | Passend zum Thema zieht das Terminal Nachrichten aus **mehreren Quellen**, trianguliert und entdoppelt - damit die Headlines wenigstens *etwas* Substanz haben |
| **Raketen-Radar**    | Ein rotes Aufleuchten einer Headline um euch klar zu machen, wo ihr gerade massive Gewinne verpasst habt.                                                      |
| **Fear & Greed-Tacho** | CNNs FEAR-and-GREED-Index als Tacho im Reddit-Header, damit ihr wisst, ob eure FOMO wenigstens Gesellschaft hat.                                               |
| **Live News-Ticker** | Financial Juice & EUR/USD-Kurs laufen direkt mit - damit ihr wisst, warum euer Depot blutet, noch bevor es im Subreddit steht.                                 |
| **Marktzeiten**      | Internationale Marktzeiten samt Urlaubstage direkt in der Fußnote. Wahre Affen wissen wann sie liquidiert werden.                                              |
| **100 % Lokal**      | Die KI läuft komplett auf eurer Kiste. Keine Cloud, kein Abo, kein fremder Mensch - eure Verluste bleiben streng privat.                                       |

### 📸 Loss Porn (Visuals)

<p align="center">
  <img width="850" alt="Dashboard" src="https://github.com/user-attachments/assets/68145c7f-b869-45de-b321-2246d7761edb" />
  <br>
  <em>Hier könnte Ihre Werbung stehen (wenn ihr noch Geld hättet).</em>
</p>

### 🚀 Das perfekte Setup (Buy High, Sell Low)

Sind wir mal ehrlich: Wenn euer *Trade-Setup* im Nachhinein mal wieder kompletter Müll war und der Hebel euer halbes Depot liquidiert hat, sollt ihr euch hier wenigstens nicht mit noch einem beschissenen Setup herumquälen müssen. 

Deshalb ist unser *Software-Setup* komplett idiotensicher. Der Installer nimmt euch an die Hand, lädt im Hintergrund vollautomatisch **Ollama** runter und ballert euch direkt die passenden KI-Modelle auf die Platte, sodass ihr euch voll und ganz auf eure Rachehebel konzentrieren könnt.

<p align="center">
  <img width="450" alt="Screenshot vom Installer" src="https://github.com/user-attachments/assets/8be4922e-7c13-4960-9e92-ce0b2d6c0ff3">
  <br>
</p>

> [!NOTE]
> Weil wir unser ganzes Geld in Optionen verzockt haben, statt die Apple- und Microsoft-Mafia für ihre teuren Developer-Zertifikate zu bezahlen (*Signing Culture ist Fettwerk*), werden eure Kisten beim Installieren erstmal meckern, dass das Terminal ein böser Virus sei. Keine Panik:
> - **Mac-Affen:** Oben rechts aufs **"?"** am Fenster klicken, dann auf den Link zu Datenschutz & Sicherheit. Runterscrollen und auf **"Trotzdem öffnen"** klicken (oder einfach in die Mac-Settings zu Datenschutz & Sicherheit scrollen). Im Zweifel: Google, lol.
> - **Windows-Degenerates:** Blauer SmartScreen poppt auf ➔ Klickt auf **"Weitere Informationen"** ➔ **"Trotzdem ausführen"**.

### 💻 Systemanforderungen (Der Gerät)

Euer Computer sollte nicht nur schön blinken, sondern auch performen können. Ollama zieht gut was weg, besonders wenn das Affengeschrei groß ist, weil die Fetten kommen.

| Spec | Anforderung                                                                                                                                                                                               |
| :--- |:----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **OS** | Mac (Apple Silicon bevorzugt, M-Series fetzt), Windows (AMD/NVIDIA Long) oder Linux.                                                                                                                      |
| **RAM** | Mindestens **16 GB**, sonst läuft das Terminal genau wie eure Trades - nicht gut. Für High-Speed Headlines solltet ihr euch [mehr RAM installieren](https://downloadmoreram.com/index.html). |
| **Speicherplatz** | **≈ 15 GB frei** (das Terminal lädt ein lokales KI-Modell plus Embedding-Modell auf die Platte, und die fressen nun mal Speicher).                                                                        |
| **Prozessor** | M1/M2/M3/M4 aufwärts oder ein vernünftiger Mehrkerner mit GPU-Support. Ohne NPU/GPU ist die Textgenerierung langsamer als Trade Republic im Dip.                                                          |

### 💎 Mitmachen

Macht Pull Requests, fixet Bugs oder baut neue Features. Aber fasst euch kurz, wir haben keine Aufmerksamkeitsspanne.



```bash
# Repo klonen (nicht screenshotten, eintippen!)
git clone https://github.com/bsommerfeld/wsbg-terminal.git

# Bauen (hoffentlich ohne rote Fehler, wir hassen rot)
mvn clean install

# Abfahrt (für die ganz Faulen)
./.script/run.sh
```
[![Static Badge](https://img.shields.io/badge/Code%20of%20Conduct-green)](CODE_OF_CONDUCT.md) [![Static Badge](https://img.shields.io/badge/Contributing-green)](CONTRIBUTING.md)

**Keine Anlageberatung.** Ich mag nur die Aktie. 🖍️
