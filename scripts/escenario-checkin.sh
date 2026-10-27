#!/usr/bin/env bash
# Escenario de check-in para probar y ensayar la demo de HU-308, sobre el seed de desarrollo.
# Arma, si no está, el "Torneo con check-in" (dev-torneo-checkin): individual y EN_CURSO, con dev_jugador1 a 4, el
# árbitro y las dos semifinales (dev_jugador1 contra dev_jugador4 y dev_jugador2 contra dev_jugador3), y la final vacía.
# Por la API, dev_organizador configura la ventana (abre 30 minutos antes y dura 20) y programa las semifinales que
# siguen PROGRAMADO, relativas a ahora:
#   - semifinal 1 dentro de 20 minutos: la ventana está abierta;
#   - semifinal 2 dentro de una hora: abre en media hora (con --cerrada, hace 5 minutos que cerró).
# Correrlo de nuevo vuelve a programarlas desde ahora. Un check-in hecho deja la fecha fija: esa no se toca.
#
#   scripts/escenario-checkin.sh [--cerrada]
#
# Variables: PICA_URL (default http://localhost:8080), PICA_DB_CONTAINER (default tournament_postgres),
# PICA_DB_NAME (default tournament_db).
# Necesita la app levantada con el perfil seed, curl y jq.

set -euo pipefail

URL=${PICA_URL:-http://localhost:8080}/api/v1
CONTENEDOR=${PICA_DB_CONTAINER:-tournament_postgres}
BASE=${PICA_DB_NAME:-tournament_db}
CLAVE=PicaDev2026
SEGUNDA=60
[[ ${1:-} == --cerrada ]] && SEGUNDA=5

sql() { docker exec -i "$CONTENEDOR" psql -U postgres -d "$BASE" -tAc "$1"; }

# el torneo, su árbitro, sus inscripciones y el bracket, como el Torneo cerrado del seed
sql "
INSERT INTO torneo (organizador_id, nombre, descripcion, reglamento_url, reglamento_hash, disciplina, formato,
                    modalidad, tipo_participante, sede, cupo_maximo, cupo_minimo, valor_inscripcion, pct_pozo_competidores,
                    pct_premio_rifa, cierre_inscripcion_en, fecha_inicio, estado, slug, esquema_hash, publicado_en,
                    lista_hash, lista_sellada_en, semilla_bracket, creado_por, modificado_por)
SELECT u.id, 'Torneo con check-in', 'Torneo individual de ajedrez para probar la ventana de check-in',
       'https://pica.local/reglamentos/dev-torneo-checkin.pdf',
       encode(sha256(convert_to('reglamento dev-torneo-checkin', 'UTF8')), 'hex'), 'Ajedrez', 'ELIMINACION_DIRECTA',
       'PRESENCIAL', 'INDIVIDUAL', 'Club UNLu', 8, 4, 2000.00, 70, 20, now() - interval '2 days',
       now() - interval '1 day', 'EN_CURSO', 'dev-torneo-checkin',
       encode(sha256(convert_to('esquema dev-torneo-checkin', 'UTF8')), 'hex'), now() - interval '10 days',
       encode(sha256(convert_to('lista dev-torneo-checkin', 'UTF8')), 'hex'), now() - interval '2 days',
       'semilla-dev-torneo-checkin', 'SEED', 'SEED'
FROM usuario u WHERE u.username = 'dev_organizador'
ON CONFLICT DO NOTHING;

INSERT INTO torneo_arbitro (torneo_id, usuario_id, estado)
SELECT t.id, u.id, 'ACEPTADO' FROM torneo t, usuario u
WHERE t.slug = 'dev-torneo-checkin' AND u.username = 'dev_arbitro'
ON CONFLICT DO NOTHING;

INSERT INTO inscripcion (torneo_id, usuario_id, estado, reglamento_aceptado_en, creado_por, modificado_por)
SELECT t.id, u.id, 'CONFIRMADA', now() - interval '5 days', 'SEED', 'SEED' FROM torneo t, usuario u
WHERE t.slug = 'dev-torneo-checkin'
  AND u.username IN ('dev_jugador1', 'dev_jugador2', 'dev_jugador3', 'dev_jugador4')
ON CONFLICT DO NOTHING;

INSERT INTO partido (torneo_id, arbitro_id, ronda, posicion, creado_por, modificado_por)
SELECT t.id, a.id, v.ronda, v.posicion, 'SEED', 'SEED'
FROM (VALUES (1, 1), (1, 2), (2, 1)) AS v (ronda, posicion)
CROSS JOIN torneo t
JOIN usuario a ON a.username = 'dev_arbitro'
WHERE t.slug = 'dev-torneo-checkin'
ON CONFLICT DO NOTHING;

INSERT INTO juega (partido_id, inscripcion_id, lado, creado_por, modificado_por)
SELECT p.id, i.id, v.lado, 'SEED', 'SEED'
FROM (VALUES (1, 'dev_jugador1', 'LOCAL'),
             (1, 'dev_jugador4', 'VISITANTE'),
             (2, 'dev_jugador2', 'LOCAL'),
             (2, 'dev_jugador3', 'VISITANTE')) AS v (posicion, username, lado)
JOIN torneo t ON t.slug = 'dev-torneo-checkin'
JOIN partido p ON p.torneo_id = t.id AND p.ronda = 1 AND p.posicion = v.posicion
JOIN usuario u ON u.username = v.username
JOIN inscripcion i ON i.torneo_id = t.id AND i.usuario_id = u.id
ON CONFLICT DO NOTHING;" >/dev/null

TORNEO=$(sql "SELECT id FROM torneo WHERE slug = 'dev-torneo-checkin'")
[[ $TORNEO =~ ^[0-9]+$ ]] || { echo "✗ no se pudo armar el Torneo con check-in"; exit 1; }

ORG=$(curl -sf -X POST "$URL/auth/login" -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg c "$CLAVE" '{identificador: "dev_organizador", password: $c}')" | jq -r .accessToken)
put() { curl -sf -o /dev/null -H "Authorization: Bearer $ORG" -H 'Content-Type: application/json' -X PUT "$URL$1" -d "$2"; }

put "/torneos/$TORNEO/checkin" '{"minAntes":30,"duracion":20}'
echo "Torneo con check-in ($TORNEO): abre 30 minutos antes del partido y dura 20"

programar() { # posicion, minutos desde ahora
    local p
    p=$(sql "SELECT id FROM partido WHERE torneo_id = $TORNEO AND ronda = 1 AND posicion = $1")
    if [[ $(sql "SELECT estado FROM partido WHERE id = $p") != PROGRAMADO ]]; then
        echo "  semifinal $1 (partido $p): ya hicieron check-in, la fecha queda fija"
        return
    fi
    put "/partidos/$p/programacion" "$(jq -nc --arg f "$(date -u -d "+$2 minutes" +%Y-%m-%dT%H:%M:%SZ)" \
        '{fechaProgramada: $f}')"
    echo "  semifinal $1 (partido $p): dentro de $2 minutos"
}

programar 1 20
programar 2 "$SEGUNDA"
