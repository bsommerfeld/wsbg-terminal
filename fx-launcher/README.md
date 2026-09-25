# Launcher

## Drittanwendungen

Drittanwendungen, die das Terminal mitbringt oder nutzt (etwa später llama.cpp), werden **ausschließlich zusammen mit der Anwendung aktualisiert** - also nur, wenn die Anwendung selbst ein Update hat.

- Der Launcher aktualisiert sie nie eigenständig, auch nicht beim Start.
- Welche Version einer Drittanwendung läuft, legt das Release der Anwendung fest.

Früher wurde Ollama bei jedem Start aktualisiert, sobald möglich. Das entfällt: ein Terminal-Release soll immer mit genau den Drittanwendungen laufen, mit denen es gebaut und geprüft wurde.
