#!/bin/bash
# Instalador CaptionFlow para macOS
set -e
SRC="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$HOME/Library/Application Support/Adobe/CEP/extensions/com.joohn.captionflow"
mkdir -p "$(dirname "$DEST")"
rm -rf "$DEST"
cp -R "$SRC" "$DEST"
rm -rf "$DEST/installer" "$DEST/tests"
for v in 9 10 11 12; do defaults write com.adobe.CSXS.$v PlayerDebugMode 1; done
echo "CaptionFlow instalado em: $DEST"
echo "Reinicie o Premiere Pro e abra: Janela > Extensoes > CaptionFlow"
