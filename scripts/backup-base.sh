#!/usr/bin/env bash
# Backup de la base (PICA-357, RNF-DIS-03): pg_dump del esquema public, cifrado con age. Lo corre todos los días
# .github/workflows/backup.yml; el restore está en scripts/restaurar-backup.sh y docs/backup-restore.md.
#
#   SUPABASE_DB_URL=postgresql://usuario@host:5432/postgres PGPASSWORD=... BACKUP_AGE_RECIPIENT=age1... \
#     scripts/backup-base.sh <carpeta>
#
# Deja en <carpeta> un solo archivo, pica-<fecha UTC>.dump.age, e imprime "archivo=<nombre>" (para $GITHUB_OUTPUT).
# El dump sin cifrar vive en una carpeta temporal que se borra al salir. Nunca imprime la URL de la base.
#
# Solo el esquema public: ahí están todas las tablas de PICA y el historial de Flyway. auth y storage son de
# Supabase (no los usamos para usuarios) y los archivos de los buckets no entran en un pg_dump.

set -euo pipefail

: "${SUPABASE_DB_URL:?falta SUPABASE_DB_URL}"
: "${BACKUP_AGE_RECIPIENT:?falta BACKUP_AGE_RECIPIENT}"
SALIDA=${1:?uso: $0 <carpeta de salida>}
# pg_dump tiene que ser de la misma versión mayor que la base o más nueva
PG_MAYOR=${PG_MAYOR:-17}

# La contraseña va aparte, en PGPASSWORD: así no hay que codificarla. Si la URL no tiene la forma esperada (por
# ejemplo, trae una contraseña con @ o /), psql imprimiría el pedazo que tomó como host, y el repo es público: se
# corta acá sin mostrarla.
: "${PGPASSWORD:?falta PGPASSWORD, la contraseña de la base}"
export PGPASSWORD
if [[ ! $SUPABASE_DB_URL =~ ^postgres(ql)?://[^:@/?#]+@[^@/:?#]+:[0-9]+/[^@/?#]+(\?[^@/#]*)?$ ]]; then
    echo "SUPABASE_DB_URL tiene que ser postgresql://usuario@host:puerto/base, sin la contraseña" >&2
    exit 1
fi

servidor=$(psql "$SUPABASE_DB_URL" -XAtc 'SHOW server_version_num')
if (( servidor / 10000 > PG_MAYOR )); then
    echo "La base es Postgres $(( servidor / 10000 )) y pg_dump es $PG_MAYOR: subí PG_MAYOR en el workflow" >&2
    exit 1
fi

claro=$(mktemp -d)
trap 'rm -rf "$claro"' EXIT

pg_dump "$SUPABASE_DB_URL" --schema=public --no-owner --no-privileges --format=custom --file="$claro/pica.dump"

# un dump sin datos (base equivocada, permisos) no es un backup
tablas=$(pg_restore --list "$claro/pica.dump" | grep -c ' TABLE DATA public ' || true)
if (( tablas == 0 )); then
    echo "El dump no trae datos de ninguna tabla" >&2
    exit 1
fi
version=$(psql "$SUPABASE_DB_URL" -XAtc \
    'SELECT version FROM public.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1')

mkdir -p "$SALIDA"
nombre="pica-$(date -u +%Y-%m-%dT%H%MZ).dump.age"
age --recipient "$BACKUP_AGE_RECIPIENT" --output "$SALIDA/$nombre" "$claro/pica.dump"

tamanio=$(du -h "$SALIDA/$nombre" | cut -f1)
echo "Backup $nombre: $tablas tablas, migración V$version, $tamanio cifrado" >&2
if [[ -n ${GITHUB_STEP_SUMMARY:-} ]]; then
    {
        echo "### Backup de la base"
        echo "| Archivo | Tablas | Migración | Postgres | Tamaño |"
        echo "|---|---|---|---|---|"
        echo "| \`$nombre\` | $tablas | V$version | $(( servidor / 10000 )) | $tamanio |"
    } >> "$GITHUB_STEP_SUMMARY"
fi
echo "archivo=$nombre"
