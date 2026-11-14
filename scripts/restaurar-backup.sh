#!/usr/bin/env bash
# Restaura un backup de la base (PICA-357) en otra base Postgres vacía, por ejemplo un proyecto nuevo de Supabase.
# Los pasos completos están en docs/backup-restore.md.
#
#   RESTORE_DB_URL='postgresql://postgres.<ref>@aws-0-<región>.pooler.supabase.com:5432/postgres' \
#     scripts/restaurar-backup.sh [<id de la corrida del workflow Backup> | <archivo .dump.age>]
#
# Sin argumento baja el último backup exitoso. Necesita gh (con sesión), age, docker y la clave privada en
# BACKUP_AGE_KEY (por defecto ~/pica-backup.key). Los clientes de Postgres corren en la imagen postgres:<PG_MAYOR>.
# La contraseña la pide una vez (o la toma de PGPASSWORD). No borra nada: si la base destino ya tiene las tablas,
# falla en la primera.

set -euo pipefail

: "${RESTORE_DB_URL:?falta RESTORE_DB_URL, la URI de la base donde restaurar}"
CLAVE=${BACKUP_AGE_KEY:-$HOME/pica-backup.key}
PG_MAYOR=${PG_MAYOR:-17}
REPO=hydra-sip/pica-back

[[ -r $CLAVE ]] || { echo "No encuentro la clave privada en $CLAVE (BACKUP_AGE_KEY)" >&2; exit 1; }
if [[ -z ${PGPASSWORD:-} ]]; then
    read -rsp "Contraseña de la base destino: " PGPASSWORD && echo >&2
fi
export PGPASSWORD
inicio=$(date +%s)
dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT

if [[ -f ${1:-} ]]; then
    cifrado=$1
else
    corrida=${1:-$(gh run list -R "$REPO" --workflow backup.yml --status success --limit 1 \
        --json databaseId --jq '.[0].databaseId')}
    [[ -n $corrida ]] || { echo "No hay ningún backup exitoso en $REPO" >&2; exit 1; }
    echo "Bajando el backup de la corrida $corrida..." >&2
    gh run download "$corrida" -R "$REPO" --dir "$dir/bajado"
    cifrado=$(find "$dir/bajado" -type f -name '*.dump.age' | sort | tail -1)
fi
echo "Descifrando $(basename "$cifrado")..." >&2
age --decrypt --identity "$CLAVE" --output "$dir/pica.dump" "$cifrado"

pg() { docker run --rm --network host -e PGPASSWORD -v "$dir:/backup" "postgres:$PG_MAYOR" "$@"; }

# el esquema public ya existe en la base destino (y no es nuestro): se restaura todo lo que tiene adentro
pg pg_restore --list /backup/pica.dump | grep -vE ' SCHEMA - public | COMMENT - SCHEMA public ' > "$dir/lista"
echo "Restaurando..." >&2
pg pg_restore --no-owner --no-privileges --exit-on-error --use-list /backup/lista --dbname "$RESTORE_DB_URL" \
    /backup/pica.dump

pg psql "$RESTORE_DB_URL" -X -c "
    SELECT (SELECT 'V' || version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1)
               AS migracion,
           (SELECT count(*) FROM usuario) AS usuarios,
           (SELECT count(*) FROM torneo) AS torneos,
           (SELECT count(*) FROM movimiento) AS movimientos"
echo "Restaurado en $(( $(date +%s) - inicio )) s" >&2
