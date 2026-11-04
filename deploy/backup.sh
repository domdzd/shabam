#!/usr/bin/env sh
# Consistent online backup of the NanoTune SQLite database into ./backups/.
# Usage: ./backup.sh   (run from the deploy/ directory; keep the last 14 backups)
set -eu
mkdir -p backups
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
docker exec nanotune-api python -c "
import sqlite3
src = sqlite3.connect('/data/nanotune.db')
dst = sqlite3.connect('/tmp/backup.db')
src.backup(dst)
dst.close(); src.close()
"
docker cp nanotune-api:/tmp/backup.db "backups/nanotune-$STAMP.db"
docker exec nanotune-api rm -f /tmp/backup.db
ls -1t backups/nanotune-*.db | tail -n +15 | xargs -r rm --
echo "Backup written to backups/nanotune-$STAMP.db"
