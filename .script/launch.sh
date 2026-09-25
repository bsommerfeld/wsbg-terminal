#!/bin/bash

# Starts the installed terminal. Ships in the update package under bin/, next
# to lib/ with every module the terminal needs. Java comes from JAVA_HOME (a
# launcher points it at its bundled runtime), else from the PATH; it has to be
# Java 27 or newer.
#
# ALL-MODULE-PATH: jars without a module-info (jakarta.inject, Guice's own
# dependencies) are automatic modules, and no explicit module requires some of
# them - left out, Guice fails on jakarta/inject/Provider at start.
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

exec "$JAVA" \
    --enable-native-access=javafx.graphics,de.bsommerfeld.wsbg.orb \
    --module-path "$APP_DIR/lib" \
    --add-modules ALL-MODULE-PATH \
    --module de.bsommerfeld.wsbg.terminal/de.bsommerfeld.wsbg.terminal.TerminalApp \
    "$@"
