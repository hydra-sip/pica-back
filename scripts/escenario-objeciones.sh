#!/usr/bin/env bash
# Escenario de objeciones para probar y ensayar la demo de HU-303 y HU-304, sobre el seed de desarrollo.
# Pone el "Torneo cerrado" (dev-torneo-cerrado) EN_CURSO y, por la API, deja sus dos semifinales objetadas:
#   - semifinal 1: dev_jugador1 carga 3-1 con una foto; dev_jugador4 sube otra y objeta 1-1 (4-2 por penales);
#   - semifinal 2: dev_jugador2 carga 2-0; dev_jugador3 sube una foto y objeta 0-2.
# Con --laudar, dev_organizador lauda la semifinal 1 con lo que propuso dev_jugador4, citando su foto.
# Por la API, así quedan los archivos de evidencia y los registros de auditoría como en uso real.
#
#   scripts/escenario-objeciones.sh [--laudar]
#
# Variables: PICA_URL (default http://localhost:8080), PICA_DB_CONTAINER (default tournament_postgres),
# PICA_DB_NAME (default tournament_db).
# Necesita la app levantada con el perfil seed, curl y jq. Si una semifinal ya tiene resultado, no la toca.

set -euo pipefail

URL=${PICA_URL:-http://localhost:8080}/api/v1
CONTENEDOR=${PICA_DB_CONTAINER:-tournament_postgres}
BASE=${PICA_DB_NAME:-tournament_db}
CLAVE=PicaDev2026
LAUDAR=false
[[ ${1:-} == --laudar ]] && LAUDAR=true

sql() { docker exec -i "$CONTENEDOR" psql -U postgres -d "$BASE" -tAc "$1"; }

TORNEO=$(sql "SELECT id FROM torneo WHERE slug = 'dev-torneo-cerrado'")
[[ $TORNEO =~ ^[0-9]+$ ]] || { echo "✗ no está el Torneo cerrado del seed"; exit 1; }
sql "UPDATE torneo SET estado = 'EN_CURSO' WHERE id = $TORNEO AND estado = 'INSCRIPCION_CERRADA'" >/dev/null

token() {
    curl -sf -X POST "$URL/auth/login" -H 'Content-Type: application/json' \
        -d "$(jq -nc --arg u "$1" --arg c "$CLAVE" '{identificador: $u, password: $c}')" | jq -r .accessToken
}

# un JPG mínimo: la firma y ceros
FOTO=$(mktemp --suffix=.jpg)
trap 'rm -f "$FOTO"' EXIT
{ printf '\xff\xd8\xff\xe0'; head -c 2048 /dev/zero; } >"$FOTO"

subir() { curl -sf -H "Authorization: Bearer $1" -X POST "$URL/partidos/$2/evidencias" -F "archivo=@$FOTO" | jq -r .id; }
post() { curl -sf -o /dev/null -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -X POST "$URL$2" -d "$3"; }

partido() { sql "SELECT id FROM partido WHERE torneo_id = $TORNEO AND ronda = 1 AND posicion = $1"; }
estado() { sql "SELECT estado FROM partido WHERE id = $1"; }

objetar() { # posicion, carga, foto de quien carga (si/no), resultado cargado, objeta, objeción
    local p
    p=$(partido "$1")
    if [[ $(estado "$p") != PROGRAMADO ]]; then
        echo "  semifinal $1: ya tiene resultado ($(estado "$p")), no se toca"
        return
    fi
    local carga objeta
    carga=$(token "$2")
    objeta=$(token "$5")
    [[ $3 == si ]] && subir "$carga" "$p" >/dev/null
    post "$carga" "/partidos/$p/resultado" "$4"
    subir "$objeta" "$p" >/dev/null
    post "$objeta" "/partidos/$p/objecion" "$6"
    echo "  semifinal $1 (partido $p): objetada"
}

echo "Torneo cerrado ($TORNEO) EN_CURSO"
objetar 1 dev_jugador1 si '{"puntajeLocal":3,"puntajeVisitante":1}' dev_jugador4 \
    '{"motivo":"Terminó 1-1 y se definió por penales 4-2: la planilla firmada lo muestra.","puntajeLocal":1,"puntajeVisitante":1,"penalesLocal":4,"penalesVisitante":2}'
objetar 2 dev_jugador2 no '{"puntajeLocal":2,"puntajeVisitante":0}' dev_jugador3 \
    '{"motivo":"Cargó el resultado invertido.","puntajeLocal":0,"puntajeVisitante":2}'

if $LAUDAR; then
    P1=$(partido 1)
    if [[ $(estado "$P1") == OBJETADO ]]; then
        ORG=$(token dev_organizador)
        FOTO_OBJETA=$(sql "SELECT e.id FROM evidencia e JOIN juega j ON j.inscripcion_id = e.inscripcion_id
                           AND j.partido_id = e.partido_id WHERE e.partido_id = $P1 AND j.lado = 'VISITANTE'
                           ORDER BY e.id LIMIT 1")
        post "$ORG" "/partidos/$P1/laudo" "$(jq -nc --argjson e "$FOTO_OBJETA" '{puntajeLocal: 1, puntajeVisitante: 1,
            penalesLocal: 4, penalesVisitante: 2, justificacion: "La planilla firmada muestra 1-1 y penales 4-2.",
            evidenciaId: $e}')"
        echo "  semifinal 1: laudada (CORREGIDO)"
    else
        echo "  semifinal 1: no está OBJETADA ($(estado "$P1")), no se lauda"
    fi
fi
