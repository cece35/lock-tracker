#!/usr/bin/env bash
# Régénère le rapport SkinGap et le publie dans le dossier static/ du serveur TradeLock.
# Ne modifie jamais SkinGap.java : on ajoute juste un petit script d'auto-refresh à la
# sortie HTML avant de la publier, via sed, sans toucher au fichier source.
#
# Utilisation (voir crontab dans le README) :
#   /opt/tradelock/scripts/generate_skingap.sh

set -euo pipefail

APP_DIR="/opt/tradelock"
SKINGAP_SRC="$APP_DIR/skingap/SkinGap.java"
WORK_TMP="$(mktemp -d)"
OUT_FILE="$WORK_TMP/skingap.html"
FINAL_DEST="$APP_DIR/server/static/skingap.html"

cd "$WORK_TMP"
echo "[$(date -Is)] génération du rapport SkinGap..."

# java 11+ sait exécuter un .java directement (single-file source-code program),
# pas besoin de compiler à part.
java "$SKINGAP_SRC" "$OUT_FILE"

# Auto-refresh de la page toutes les 5 min, injecté juste avant </body> sans toucher
# au générateur Java lui-même.
sed -i 's#</body>#<script>setTimeout(()=>location.reload(),300000)</script></body>#' "$OUT_FILE"

mkdir -p "$(dirname "$FINAL_DEST")"
# Copie atomique : on écrit à côté puis on renomme, pour ne jamais servir un fichier
# à moitié écrit pendant la génération.
cp "$OUT_FILE" "$FINAL_DEST.tmp"
mv "$FINAL_DEST.tmp" "$FINAL_DEST"

rm -rf "$WORK_TMP"
echo "[$(date -Is)] OK — $FINAL_DEST mis à jour."
