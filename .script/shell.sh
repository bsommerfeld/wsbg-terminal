#!/bin/bash
# Starts the terminal with the new shell (shell/): the Java backend runs as a sidecar
# without a window of its own (WSBG_SHELL=external), the page opens in the system
# webview. Extra arguments go to the shell, e.g. --hz120 (render at display rate).
set -e
cd "$(dirname "$0")/.."
export PATH="$HOME/.cargo/bin:$PATH"
[ -d terminal/target/classes ] || mvn -q -pl terminal compile
[ -s terminal/target/shell-classpath.txt ] || mvn -q -pl terminal dependency:build-classpath \
    -Dmdep.outputFile=target/shell-classpath.txt -Dmdep.includeScope=runtime
[ -x shell/target/debug/wsbg-shell ] || (cd shell && cargo build)
exec shell/target/debug/wsbg-shell "$@" --backend java \
    -XX:+UseZGC -Xmx4g --enable-native-access=ALL-UNNAMED \
    --add-opens=java.desktop/sun.awt=ALL-UNNAMED \
    --add-opens=java.desktop/sun.lwawt=ALL-UNNAMED \
    --add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED \
    --add-opens=java.desktop/java.awt.peer=ALL-UNNAMED \
    -cp "terminal/target/classes:$(cat terminal/target/shell-classpath.txt)" \
    de.bsommerfeld.wsbg.terminal.ui.AppMain
