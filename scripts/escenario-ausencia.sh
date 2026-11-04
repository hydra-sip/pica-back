#!/usr/bin/env bash
# Escenario de ausencia para probar y ensayar la demo de HU-309, sobre el seed de desarrollo.
# Pone el "Torneo cerrado" (dev-torneo-cerrado) EN_CURSO desde ayer y, por la API, dev_organizador configura el
# check-in (abre 30 minutos antes del partido y dura 10) y programa las dos semifinales para que la ventana cierre
# dentro de N minutos (5 por defecto, hasta 12: la ventana tiene que estar abierta para el check-in):
#   - semifinal 1: dev_jugador1 hace check-in y dev_jugador4 no: dev_jugador1 gana por ausencia (WALKOVER);
#   - semifinal 2: ninguno de los dos hace check-in: se aplica el criterio sin presentes.
# El criterio es REPROGRAMAR (la semifinal 2 queda sin fecha); con --descalificar, DESCALIFICAR_AMBOS (la semifinal 2
# queda WALKOVER sin ganador y dev_jugador1 pasa directo en la final: es campeón).
# La tarea declara las ausencias 2 minutos después del cierre (la tolerancia), en su próxima vuelta (cada 30
# segundos). Si una semifinal ya no está PROGRAMADO, no la toca.
#
#   scripts/escenario-ausencia.sh [--descalificar] [minutos]
#
# Variables: PICA_URL (default http://localhost:8080), PICA_DB_CONTAINER (default tournament_postgres),
# PICA_DB_NAME (default tournament_db).
# Necesita la app levantada con el perfil seed, curl y jq.

set -euo pipefail

URL=${PICA_URL:-http://localhost:8080}/api/v1
CONTENEDOR=${PICA_DB_CONTAINER:-tournament_postgres}
BASE=${PICA_DB_NAME:-tournament_db}
CLAVE=PicaDev2026
CRITERIO=REPROGRAMAR
if [[ ${1:-} == --descalificar ]]; then
    CRITERIO=DESCALIFICAR_AMBOS
    shift
fi
MINUTOS=${1:-5}
[[ $MINUTOS =~ ^[0-9]+$ ]] && ((MINUTOS >= 1 && MINUTOS <= 12)) || { echo "✗ minutos entre 1 y 12"; exit 1; }

sql() { docker exec -i "$CONTENEDOR" psql -U postgres -d "$BASE" -tAc "$1"; }

TORNEO=$(sql "SELECT id FROM torneo WHERE slug = 'dev-torneo-cerrado'")
[[ $TORNEO =~ ^[0-9]+$ ]] || { echo "✗ no está el Torneo cerrado del seed"; exit 1; }
# empezó ayer, para programar las semifinales dentro de unos minutos
sql "UPDATE torneo SET estado = 'EN_CURSO', cierre_inscripcion_en = now() - interval '2 days',
                       fecha_inicio = now() - interval '1 day'
     WHERE id = $TORNEO AND estado IN ('INSCRIPCION_CERRADA', 'EN_CURSO')" >/dev/null

token() {
    curl -sf -X POST "$URL/auth/login" -H 'Content-Type: application/json' \
        -d "$(jq -nc --arg u "$1" --arg c "$CLAVE" '{identificador: $u, password: $c}')" | jq -r .accessToken
}
ORG=$(token dev_organizador)
put() { curl -sf -o /dev/null -H "Authorization: Bearer $ORG" -H 'Content-Type: application/json' -X PUT "$URL$1" -d "$2"; }

put "/torneos/$TORNEO/checkin" "$(jq -nc --arg c "$CRITERIO" '{minAntes: 30, duracion: 10, sinPresentes: $c}')"
echo "Torneo cerrado ($TORNEO) EN_CURSO: el check-in abre 30 minutos antes y dura 10; sin presentes, $CRITERIO"

# la ventana cierra a los 10 minutos de abrir, 20 antes del partido
PARTIDO_EN=$((MINUTOS + 20))
FECHA=$(date -u -d "+$PARTIDO_EN minutes" +%Y-%m-%dT%H:%M:%SZ)
CIERRA=$(date -d "+$MINUTOS minutes" +%H:%M)
DECLARA=$(date -d "+$((MINUTOS + 2)) minutes" +%H:%M)

semifinal() { # posicion, capitán que hace check-in (o vacío)
    local p
    p=$(sql "SELECT id FROM partido WHERE torneo_id = $TORNEO AND ronda = 1 AND posicion = $1")
    if [[ $(sql "SELECT estado FROM partido WHERE id = $p") != PROGRAMADO ]]; then
        echo "  semifinal $1 (partido $p): ya no está PROGRAMADO, no se toca"
        return
    fi
    put "/partidos/$p/programacion" "$(jq -nc --arg f "$FECHA" '{fechaProgramada: $f}')"
    if [[ -n $2 ]]; then
        curl -sf -o /dev/null -H "Authorization: Bearer $(token "$2")" -X POST "$URL/partidos/$p/checkin"
        echo "  semifinal $1 (partido $p): check-in de $2; la ventana cierra a las $CIERRA, ausencia desde las $DECLARA"
    else
        echo "  semifinal $1 (partido $p): sin check-in; la ventana cierra a las $CIERRA, ausencia desde las $DECLARA"
    fi
}

semifinal 1 dev_jugador1
semifinal 2 ""
