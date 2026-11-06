#!/usr/bin/env bash
# Torneo de demo para mostrar el check-in (PICA-312), todo por la API: anda igual en local que en el deploy.
# Arma un torneo por equipos con la inscripción cerrada y el bracket generado (queda EN_CURSO), con una sola final
# programada a la hora que se pide y la ventana de check-in abriendo N minutos antes y durando hasta la hora del
# partido. Cada corrida arma un torneo nuevo; los usuarios, si ya están, se reusan (se les vuelve a poner la clave).
#
#   scripts/demo-checkin.sh CLAVE "AAAA-MM-DD HH:MM" [VENTANA]
#
#   CLAVE    identifica el torneo y sus capitanes: demo_<CLAVE>_capitan1 y 2, con demo_<CLAVE>_jugador1 y 2 en las
#            nóminas. Así cada torneo de la demo tiene sus propios usuarios y no se mezclan en "Mis inscripciones".
#   FECHA    la hora del partido, en hora argentina.
#   VENTANA  minutos de check-in antes del partido (default 60, el máximo de la plataforma).
#
# El organizador (demo_organizador) y el árbitro (demo_arbitro) son los mismos para todos los torneos. En el camino
# prueba la subida del reglamento en PDF y el cierre manual de la inscripción.
# Por el margen entre el cierre y el inicio (CE4-5), el partido tiene que quedar al menos ese margen más unos minutos
# después de ahora: el script lo avisa si no entra.
#
# Variables:
#   PICA_API             la URL del back, sin /api/v1 (default http://localhost:8080). En el deploy es la de
#                        Railway, no la del front.
#   PICA_ADMIN_USER      default admin
#   PICA_ADMIN_PASSWORD  si no está, se pide
#   PICA_DEMO_PASSWORD   la clave de los usuarios de la demo (default PicaDemo2026)
#
# Necesita bash 4, curl y jq.

set -euo pipefail
shopt -s inherit_errexit # que un paso que falla dentro de $(...) también corte

uso() { sed -n '7,13p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 1; }
[[ $# -ge 2 ]] || uso
CLAVE_TORNEO=$1
[[ $CLAVE_TORNEO =~ ^[a-z0-9]{1,10}$ ]] || { echo "✗ la clave va en minúsculas y números, hasta 10" >&2; exit 1; }
ZONA=America/Argentina/Buenos_Aires
PARTIDO_EPOCH=$(TZ=$ZONA date -d "$2" +%s) || { echo "✗ no entiendo la fecha «$2»" >&2; exit 1; }
VENTANA=${3:-60}
[[ $VENTANA =~ ^[0-9]+$ ]] && ((VENTANA >= 1)) || { echo "✗ la ventana va en minutos" >&2; exit 1; }

for comando in curl jq; do
    command -v "$comando" >/dev/null || { echo "✗ falta $comando" >&2; exit 1; }
done

API=${PICA_API:-http://localhost:8080}
API=${API%/}/api/v1
ADMIN_USER=${PICA_ADMIN_USER:-admin}
if [[ -z ${PICA_ADMIN_PASSWORD:-} ]]; then
    read -rsp "Clave de $ADMIN_USER: " PICA_ADMIN_PASSWORD
    echo
fi
CLAVE=${PICA_DEMO_PASSWORD:-PicaDemo2026}

iso() { date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ; }
local_() { TZ=$ZONA date -d "@$1" '+%d/%m %H:%M'; }

# api MÉTODO RUTA TOKEN [BODY]: imprime la respuesta; si no es 2xx, corta con el código y el cuerpo
api() {
    local metodo=$1 ruta=$2 token=$3 body=${4:-} salida codigo
    local args=(-s -w '\n%{http_code}' -X "$metodo" "$API$ruta")
    [[ -n $token ]] && args+=(-H "Authorization: Bearer $token")
    [[ -n $body ]] && args+=(-H 'Content-Type: application/json' -d "$body")
    salida=$(curl "${args[@]}")
    codigo=${salida##*$'\n'}
    salida=${salida%$'\n'*}
    if [[ $codigo != 2* ]]; then
        echo "✗ $metodo $ruta → $codigo" >&2
        printf '%s\n' "$salida" | jq . >&2 2>/dev/null || printf '%s\n' "$salida" >&2
        exit 1
    fi
    printf '%s' "$salida"
}

login() {
    api POST /auth/login "" "$(jq -nc --arg u "$1" --arg c "$2" '{identificador: $u, password: $c}')" | jq -r .accessToken
}

ADMIN=$(login "$ADMIN_USER" "$PICA_ADMIN_PASSWORD")

# --- fechas -----------------------------------------------------------------------------------------------------
MARGEN=$(api GET /admin/parametros "$ADMIN" | jq -r '.[] | select(.clave == "MARGEN_CIERRE_INICIO_MIN") | .valor | floor')
DURACION_MAX=$(api GET /admin/parametros "$ADMIN" | jq -r '.[] | select(.clave == "CHECKIN_DURACION_MAX_MIN") | .valor | floor')
((VENTANA <= DURACION_MAX)) || { echo "✗ la plataforma admite hasta $DURACION_MAX minutos de ventana" >&2; exit 1; }
AHORA=$(date +%s)
CIERRE=$((AHORA + 5 * 60)) # se cierra a mano enseguida: solo tiene que ser futuro mientras se inscriben
MINIMO=$((CIERRE + MARGEN * 60))
# el torneo empieza cuando abre la ventana, salvo que el margen lo empuje más tarde
INICIO=$((PARTIDO_EPOCH - VENTANA * 60))
((INICIO >= MINIMO)) || INICIO=$MINIMO
if ((PARTIDO_EPOCH < INICIO)); then
    echo "✗ con el margen de $MARGEN minutos entre el cierre y el inicio, el partido tiene que ser desde las" \
        "$(local_ "$INICIO")" >&2
    exit 1
fi
FIN=$((PARTIDO_EPOCH + 24 * 3600))

# --- usuarios ---------------------------------------------------------------------------------------------------
declare -A ROL
while IFS=$'\t' read -r nombre id; do ROL[$nombre]=$id; done < <(api GET '/admin/roles?size=50' "$ADMIN" |
    jq -r '(.content // .)[] | [.nombre, .id] | @tsv')

# usuario USERNAME NOMBRE APELLIDO ROL: el id; si no está lo da de alta con su persona, y siempre le pone la clave
usuario() {
    local username=$1 id dni persona
    id=$(api GET "/admin/usuarios?q=$username&size=50" "$ADMIN" |
        jq -r --arg u "$username" '(.content // .)[] | select(.username == $u) | .id' | head -1)
    if [[ -z $id ]]; then
        # un DNI fijo por username, para reusar la persona si quedó de una corrida cortada
        dni=9$(printf '%s' "$username" | cksum | cut -d' ' -f1 | tail -c 8)
        persona=$(api GET "/admin/personas?q=$dni&size=50" "$ADMIN" |
            jq -r --arg d "$dni" '(.content // .)[] | select(.nroDoc == $d) | .id' | head -1)
        if [[ -z $persona ]]; then
            persona=$(api POST /admin/personas "$ADMIN" "$(jq -nc --arg n "$2" --arg a "$3" --arg d "$dni" \
                '{nombres: $n, apellidos: $a, tipoDoc: "DNI", nroDoc: $d, fechaNacimiento: "1995-03-14"}')" | jq -r .id)
        fi
        id=$(api POST /admin/usuarios "$ADMIN" "$(jq -nc --arg u "$username" --arg c "$CLAVE" \
            --argjson p "$persona" --argjson r "${ROL[$4]}" \
            '{username: $u, email: ($u + "@example.com"), passwordTemporal: $c, estado: "ACTIVO", personaId: $p,
              roles: [$r]}')" | jq -r .id)
    else
        api PUT "/admin/usuarios/$id/password" "$ADMIN" "$(jq -nc --arg c "$CLAVE" '{password: $c}')" >/dev/null
    fi
    printf '%s' "$id"
}

echo "Usuarios (clave $CLAVE)"
ORG_ID=$(usuario demo_organizador Olga Organizadora ORGANIZADOR)
ARB_ID=$(usuario demo_arbitro Arturo Arbitro ARBITRO)
P=demo_${CLAVE_TORNEO}
CAP1_ID=$(usuario "${P}_capitan1" Carla Capitana PARTICIPANTE)
CAP2_ID=$(usuario "${P}_capitan2" Carlos Capitan PARTICIPANTE)
JUG1_ID=$(usuario "${P}_jugador1" Julia Jugadora PARTICIPANTE)
JUG2_ID=$(usuario "${P}_jugador2" Juan Jugador PARTICIPANTE)
echo "  demo_organizador, demo_arbitro, ${P}_capitan1, ${P}_capitan2, ${P}_jugador1, ${P}_jugador2"

ORG=$(login demo_organizador "$CLAVE")
ARB=$(login demo_arbitro "$CLAVE")
CAP1=$(login "${P}_capitan1" "$CLAVE")
CAP2=$(login "${P}_capitan2" "$CLAVE")

# --- torneo -----------------------------------------------------------------------------------------------------
NOMBRE="Demo check-in $CLAVE_TORNEO $(TZ=$ZONA date -d "@$PARTIDO_EPOCH" '+%d/%m %H:%M')"
echo "Torneo «$NOMBRE»"
TORNEO=$(api POST /torneos "$ORG" "$(jq -nc --arg n "$NOMBRE" --arg i "$(iso "$INICIO")" --arg f "$(iso "$FIN")" \
    --arg c "$(iso "$CIERRE")" \
    '{nombre: $n, disciplina: "Fútbol 5", descripcion: "Torneo de demo para mostrar el check-in de los capitanes",
      formato: "ELIMINACION_DIRECTA", modalidad: "PRESENCIAL", tipoParticipante: "EQUIPO", sede: "Club UNLu",
      canchas: ["Cancha 1"], fechaInicio: $i, fechaFin: $f, cierreInscripcionEn: $c, cupoMaximo: 4, cupoMinimo: 2,
      tamanioEquipo: 2, nominaMaxima: 3}')" | jq -r .id)

# un PDF mínimo de verdad, con la tabla xref bien armada
PDF=$(mktemp --suffix=.pdf)
trap 'rm -f "$PDF"' EXIT
{
    texto="BT /F1 18 Tf 72 720 Td (Reglamento del torneo de demo de check-in) Tj ET"
    objetos=("<< /Type /Catalog /Pages 2 0 R >>"
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>"
        "<< /Length ${#texto} >>"$'\n'"stream"$'\n'"$texto"$'\n'"endstream"
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    cuerpo=$'%PDF-1.4\n'
    offsets=()
    for i in "${!objetos[@]}"; do
        offsets+=("${#cuerpo}")
        cuerpo+="$((i + 1)) 0 obj"$'\n'"${objetos[$i]}"$'\n'"endobj"$'\n'
    done
    xref=${#cuerpo}
    cuerpo+="xref"$'\n'"0 $((${#objetos[@]} + 1))"$'\n'"0000000000 65535 f "$'\n'
    for o in "${offsets[@]}"; do cuerpo+="$(printf '%010d' "$o") 00000 n "$'\n'; done
    cuerpo+="trailer"$'\n'"<< /Size $((${#objetos[@]} + 1)) /Root 1 0 R >>"$'\n'"startxref"$'\n'"$xref"$'\n'"%%EOF"$'\n'
    printf '%s' "$cuerpo"
} >"$PDF"
codigo=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/torneos/$TORNEO/reglamento" \
    -H "Authorization: Bearer $ORG" -F "archivo=@$PDF;type=application/pdf")
[[ $codigo == 200 ]] || { echo "✗ subir el reglamento en PDF → $codigo" >&2; exit 1; }
echo "  reglamento en PDF subido"

api PUT "/torneos/$TORNEO/economia" "$ORG" '{"valorInscripcion": 1000, "premios": [100]}' >/dev/null
api POST "/torneos/$TORNEO/arbitros" "$ORG" "$(jq -nc --argjson u "$ARB_ID" \
    '{usuarioId: $u, permisos: ["CARGAR_RESULTADOS", "RESOLVER_OBJECIONES"]}')" >/dev/null
api POST "/torneos/$TORNEO/publicar" "$ORG" '{"aceptaTerminos": true}' >/dev/null
api POST "/designaciones/$TORNEO/aceptar" "$ARB" '{"compromisoAceptado": true}' >/dev/null
api PUT "/torneos/$TORNEO/checkin" "$ORG" "$(jq -nc --argjson v "$VENTANA" \
    '{minAntes: $v, duracion: $v, sinPresentes: "REPROGRAMAR"}')" >/dev/null
echo "  publicado, con demo_arbitro designado y la ventana de check-in configurada"

# inscribir TOKEN EQUIPO JUGADOR_ID: borrador con la nómina, confirmación y pago simulado
inscribir() {
    local id
    id=$(api POST "/torneos/$TORNEO/inscripciones" "$1" "$(jq -nc --arg e "$2" --argjson j "$3" \
        '{nombreEquipoNuevo: $e, nomina: [$j]}')" | jq -r .id)
    api PUT "/inscripciones/$id/confirmar" "$1" '{"reglamentoAceptado": true}' >/dev/null
    api POST "/inscripciones/$id/pago" "$1" >/dev/null
}
EQUIPO1="Rojos $CLAVE_TORNEO"
EQUIPO2="Azules $CLAVE_TORNEO"
inscribir "$CAP1" "$EQUIPO1" "$JUG1_ID"
inscribir "$CAP2" "$EQUIPO2" "$JUG2_ID"
echo "  inscriptos y pagos: $EQUIPO1 (${P}_capitan1) y $EQUIPO2 (${P}_capitan2)"

api POST "/torneos/$TORNEO/cerrar-inscripcion" "$ORG" >/dev/null
echo "  inscripción cerrada a mano"
PARTIDO=$(api POST "/torneos/$TORNEO/bracket" "$ORG" | jq -r ".rondas[0].partidos[0].id")
[[ $PARTIDO =~ ^[0-9]+$ ]] || { echo "✗ el bracket no trajo la final" >&2; exit 1; }
api PUT "/partidos/$PARTIDO/programacion" "$ORG" "$(jq -nc --arg f "$(iso "$PARTIDO_EPOCH")" \
    '{fechaProgramada: $f}')" >/dev/null
echo "  bracket generado (torneo EN_CURSO) y la final programada"

VENTANA_JSON=$(api GET "/partidos/$PARTIDO/checkin" "$CAP1" | jq -c .ventana)
ABRE=$(date -d "$(jq -r .abreEn <<<"$VENTANA_JSON")" +%s)
CIERRA=$(date -d "$(jq -r .cierraEn <<<"$VENTANA_JSON")" +%s)
echo
echo "Listo: torneo $TORNEO, final (partido $PARTIDO) el $(local_ "$PARTIDO_EPOCH")"
echo "  check-in de $(local_ "$ABRE") a $(local_ "$CIERRA") (con 2 minutos de tolerancia en cada punta)"
echo "  capitanes: ${P}_capitan1 ($EQUIPO1) y ${P}_capitan2 ($EQUIPO2), clave $CLAVE"
echo "  organizador demo_organizador, árbitro demo_arbitro"
