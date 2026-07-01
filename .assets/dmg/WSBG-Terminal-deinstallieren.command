#!/bin/bash
# Deinstalliert WSBG Terminal vollständig: beendet die App und entfernt die
# App selbst sowie den kompletten Datenordner (KI-Modelle, Cache,
# Einstellungen, Schlagzeilen-Archiv).
#
# Shipped inside the DMG via jpackage --mac-dmg-content: macOS has NO
# uninstall hook (a drag to Trash fires nothing), so this script is the
# only way to offer the cross-OS "uninstall wipes the data dir" behaviour.
set -u

echo "WSBG Terminal wird deinstalliert…"

osascript -e 'quit app "WSBG Terminal"' >/dev/null 2>&1 || true
sleep 2

rm -rf "/Applications/WSBG Terminal.app"
rm -rf "$HOME/Library/Application Support/wsbg-terminal"

echo "Fertig - WSBG Terminal und alle Daten wurden entfernt."
echo "Dieses Fenster kann geschlossen werden."
