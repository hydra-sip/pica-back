#!/usr/bin/env bash
# Prueba de humo del contrato (CE2-7). Recorre todas las operaciones de docs/api/openapi.yaml contra
# una API levantada, compara cada código HTTP con el que espera el contrato y al terminar borra de la
# base todo lo que creó. La API solo hace bajas lógicas: por eso la limpieza va por SQL.
#
#   scripts/prueba-de-humo.sh            # local: localhost:8080 y el Postgres de docker-compose.yml
#
#   PICA_URL=https://pica-hydra.duckdns.org \
#   PICA_SSH="-i $HOME/.ssh/pica-staging.pem ubuntu@pica-hydra.duckdns.org" \
#   scripts/prueba-de-humo.sh            # staging (el CI lo corre solo después de cada deploy)
#
# Variables:
#   PICA_URL             default http://localhost:8080
#   PICA_SSH             argumentos de ssh para llegar a la instancia; la limpieza corre psql en el
#                        contenedor db de /opt/pica. Sin esto usa el Postgres local.
#   PICA_ADMIN_USER      default admin
#   PICA_ADMIN_PASSWORD  si no está: con PICA_SSH se lee del .env de la instancia; si no, se pide
#   PICA_DB_CONTAINER    contenedor del Postgres local, default tournament_postgres
#
# Necesita bash 4, curl, jq y openssl. Sale con 1 si un paso no da lo que dice el contrato, si
# queda una operación sin probar o si la limpieza deja algo.

set -euo pipefail

RAIZ=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
CONTRATO="$RAIZ/docs/api/openapi.yaml"
URL=${PICA_URL:-http://localhost:8080}
URL=${URL%/}
ADMIN_USER=${PICA_ADMIN_USER:-admin}

for comando in curl jq openssl; do
    command -v "$comando" >/dev/null || { echo "Falta $comando" >&2; exit 1; }
done

ES_LOCAL='^https?://(localhost|127\.0\.0\.1)(:|/|$)'
if [[ -z ${PICA_SSH:-} && ! $URL =~ $ES_LOCAL ]]; then
    echo "Para limpiar en $URL hace falta PICA_SSH (sin eso limpiaría la base local)" >&2
    exit 1
fi

# --- Marcas de la corrida ----------------------------------------------------------------------
# Todo lo que se crea las lleva y la limpieza borra solo eso. Se conocen antes de mandar cada
# pedido, así que se limpia aunque el script se corte sin leer una respuesta.

CORRIDA="humo-$(date -u +%m%d%H%M%S)$(openssl rand -hex 2)"
AGENTE="pica-$CORRIDA"              # User-Agent: marca los refresh tokens, también los del admin
ROL="HUMO_${CORRIDA#humo-}"
ROL=${ROL^^}
USER_REG="$CORRIDA-r"               # el del registro: queda pendiente de verificación
USER_ADM="$CORRIDA-u"               # el que da de alta el admin
dni() { printf '9%07d' $(( $(od -An -N4 -tu4 /dev/urandom) % 10000000 )); }
DNI_REG=$(dni)
DNI_PER=$(dni)
while [[ $DNI_PER == "$DNI_REG" ]]; do DNI_PER=$(dni); done
clave() { printf 'Humo%s7' "$(openssl rand -hex 6)"; }
CLAVE_1=$(clave)
CLAVE_2=$(clave)
CLAVE_3=$(clave)

# --- Base de datos (solo para la limpieza) -----------------------------------------------------

read -r -d '' PSQL_REMOTO <<'EOF' || true
cd /opt/pica || exit 1
if docker info >/dev/null 2>&1; then d=docker; else d="sudo -n docker"; fi
exec $d compose exec -T db sh -c 'exec psql -X -q -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
EOF

read -r -d '' CLAVE_REMOTA <<'EOF' || true
{ cat /opt/pica/.env 2>/dev/null || sudo -n cat /opt/pica/.env; } \
    | sed -n 's/^ADMIN_INITIAL_PASSWORD=//p' | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/"
EOF

# PICA_SSH son varios argumentos a propósito (-i clave usuario@host)
remoto() {
    # shellcheck disable=SC2086
    ssh -o BatchMode=yes $PICA_SSH "$1"
}

sql() {
    if [[ -n ${PICA_SSH:-} ]]; then
        remoto "$PSQL_REMOTO"
    else
        docker exec -i "${PICA_DB_CONTAINER:-tournament_postgres}" \
            psql -X -q -t -A -v ON_ERROR_STOP=1 -U postgres -d tournament_db
    fi
}

if [[ -z ${PICA_ADMIN_PASSWORD:-} ]]; then
    if [[ -n ${PICA_SSH:-} ]]; then
        PICA_ADMIN_PASSWORD=$(remoto "$CLAVE_REMOTA")
    elif [[ -t 0 ]]; then
        read -rsp "Clave de $ADMIN_USER: " PICA_ADMIN_PASSWORD
        echo
    fi
fi
[[ -n ${PICA_ADMIN_PASSWORD:-} ]] || { echo "Falta la clave del admin (PICA_ADMIN_PASSWORD)" >&2; exit 1; }
export PICA_ADMIN_PASSWORD    # jq la lee del entorno: como argumento se vería en ps
if [[ ${GITHUB_ACTIONS:-} == true ]]; then
    echo "::add-mask::$PICA_ADMIN_PASSWORD"
fi

echo "Prueba de humo contra $URL (corrida $CORRIDA)"

# Antes de crear nada: si no se puede limpiar, no se arranca. El margen de un minuto cubre una
# diferencia de reloj entre la API y la base; con las marcas únicas no alcanza a nada ajeno.
if ! previo=$(sql <<SQL
SELECT now() - interval '1 minute',
       (SELECT count(*) FROM usuario WHERE username LIKE '$CORRIDA-%')
     + (SELECT count(*) FROM persona WHERE nro_doc IN ('$DNI_REG', '$DNI_PER'))
     + (SELECT count(*) FROM rol WHERE nombre = '$ROL');
SQL
); then
    echo "No llego a la base: sin eso no se puede limpiar, así que no arranco" >&2
    exit 1
fi
IFS='|' read -r INICIO OCUPADOS <<<"$previo"
if [[ $OCUPADOS != 0 ]]; then
    echo "Algún documento o nombre de la corrida ya existe; volver a correr" >&2
    exit 1
fi

TMP=$(mktemp -d)

limpiar() {
    local estado=$?
    trap - EXIT INT TERM
    echo
    echo "Limpieza"
    # Ctrl+C también mata al curl, pero la API igual termina el pedido que ya recibió: si se limpia
    # enseguida, lo que guarda después queda afuera. Se le da tiempo, y si aun así la transacción choca
    # con algo recién guardado, se repite.
    if (( ${EN_VUELO:-0} )); then
        echo "  esperando el pedido que quedó en vuelo"
        sleep 3
    fi
    local intento salida quedan="" usuarios=0 personas=0 roles=0 tokens=0 u p r t
    for intento in 1 2 3; do
        if salida=$(borrar_corrida 2>"$TMP/sql.err"); then
            IFS='|' read -r u p r t <<<"$(sed -n 1p <<<"$salida")"
            usuarios=$((usuarios + u)) personas=$((personas + p)) roles=$((roles + r)) tokens=$((tokens + t))
            quedan=$(sed -n 2p <<<"$salida")
            [[ $quedan == 0 ]] && break
        fi
        if (( intento < 3 )); then sleep 2; fi
    done
    if [[ -z $quedan ]]; then
        sed 's/^/  /' "$TMP/sql.err" >&2
        echo "  ✗ no se pudo limpiar: buscar $CORRIDA en la base" >&2
        exit 1
    fi
    rm -rf "$TMP"
    echo "  borrados: $usuarios usuarios, $personas personas, $roles rol, $tokens refresh tokens"
    if [[ $quedan != 0 ]]; then
        echo "  ✗ quedaron $quedan filas de $CORRIDA" >&2
        exit 1
    fi
    echo "  ✓ sin rastros de la corrida en la base"
    exit "$estado"
}

# Una transacción: o se borra todo lo de la corrida o nada. Imprime lo borrado y lo que queda.
borrar_corrida() {
    sql <<SQL
BEGIN;
CREATE TEMP TABLE humo_u ON COMMIT DROP AS
    SELECT id, persona_id FROM usuario WHERE username LIKE '$CORRIDA-%' AND creado_en >= '$INICIO';
CREATE TEMP TABLE humo_p ON COMMIT DROP AS
    SELECT persona_id AS id FROM humo_u
    UNION SELECT id FROM persona WHERE nro_doc IN ('$DNI_REG', '$DNI_PER') AND creado_en >= '$INICIO';
CREATE TEMP TABLE humo_r ON COMMIT DROP AS
    SELECT id FROM rol WHERE nombre = '$ROL' AND creado_en >= '$INICIO';
CREATE TEMP TABLE humo_t ON COMMIT DROP AS
    SELECT id FROM refresh_token
    WHERE usuario_id IN (SELECT id FROM humo_u) OR (user_agent = '$AGENTE' AND creado_en >= '$INICIO');
SELECT (SELECT count(*) FROM humo_u), (SELECT count(*) FROM humo_p),
       (SELECT count(*) FROM humo_r), (SELECT count(*) FROM humo_t);
DELETE FROM refresh_token WHERE id IN (SELECT id FROM humo_t);
DELETE FROM usuario_rol WHERE usuario_id IN (SELECT id FROM humo_u) OR rol_id IN (SELECT id FROM humo_r);
DELETE FROM rol_permiso WHERE rol_id IN (SELECT id FROM humo_r);
DELETE FROM usuario WHERE id IN (SELECT id FROM humo_u);
DELETE FROM persona WHERE id IN (SELECT id FROM humo_p);
DELETE FROM rol WHERE id IN (SELECT id FROM humo_r);
COMMIT;
SELECT (SELECT count(*) FROM usuario WHERE username LIKE '$CORRIDA-%')
     + (SELECT count(*) FROM persona WHERE nro_doc IN ('$DNI_REG', '$DNI_PER'))
     + (SELECT count(*) FROM rol WHERE nombre = '$ROL')
     + (SELECT count(*) FROM refresh_token WHERE user_agent = '$AGENTE');
SQL
}
trap limpiar EXIT
trap 'exit 130' INT TERM

# --- Pedidos -----------------------------------------------------------------------------------

declare -A PROBADAS=()
RESP=""
EN_VUELO=0

# pedir ESPERADO METODO OPERACION [RUTA] [TOKEN] [BODY]
# ESPERADO es el código HTTP, o "400:CODIGO" para comprobar también el codigo del ProblemDetail.
# OPERACION es la ruta tal como figura en el contrato; RUTA, la real (con ids y query).
pedir() {
    local esperado=${1%%:*} codigo_esperado="" metodo=$2 operacion=$3 ruta=${4:-$3} token=${5:-} body=${6:-}
    [[ $1 == *:* ]] && codigo_esperado=${1#*:}
    local args=(-sS -o "$TMP/resp" -w '%{http_code}' -X "$metodo" -A "$AGENTE" --max-time 30)
    [[ -n $token ]] && args+=(-H "Authorization: Bearer $token")
    [[ -n $body ]] && args+=(-H 'Content-Type: application/json' --data-binary @-)
    : >"$TMP/resp"
    local http
    EN_VUELO=1
    http=$(printf '%s' "$body" | curl "${args[@]}" "$URL$ruta" || true)
    EN_VUELO=0
    RESP=$(<"$TMP/resp")
    local codigo=""
    [[ -n $codigo_esperado ]] && codigo=$(jq -r '.codigo // empty' <<<"$RESP" 2>/dev/null || true)
    if [[ $http != "$esperado" || $codigo != "$codigo_esperado" ]]; then
        printf '  ✗ %s %-6s %s%s  (esperaba %s)\n' "$http" "$metodo" "$ruta" "${codigo:+ $codigo}" "$1"
        [[ -n $RESP ]] && printf '    %s\n' "$(head -c 400 <<<"$RESP")"
        exit 1
    fi
    PROBADAS["$metodo $operacion"]=1
    printf '  ✓ %s %-6s %s%s\n' "$http" "$metodo" "$operacion" "${codigo:+  $codigo}"
}

campo() { jq -er "$1" <<<"$RESP"; }

# Que el listado traiga lo que se acaba de crear (la búsqueda con q anda)
en_listado() {
    jq -e --argjson id "$1" 'any(.content[]; .id == $id)' <<<"$RESP" >/dev/null \
        || { echo "    ✗ el listado no trae el id $1"; exit 1; }
}

echo
echo "Públicas"
pedir 200 GET /.well-known/jwks.json
pedir 200 GET /api/v1/auth/public-key
pedir 201 POST /api/v1/auth/registro "" "" "$(jq -nc \
    --arg u "$USER_REG" --arg c "$CLAVE_1" --arg d "$DNI_REG" \
    '{username: $u, email: ($u + "@example.com"), password: $c, nombres: "Prueba", apellidos: "Humo",
      tipoDoc: "DNI", nroDoc: $d, fechaNacimiento: "1990-05-17"}')"
# Si la base que se va a limpiar no es la de la API, mejor enterarse ahora
[[ $(sql <<<"SELECT count(*) FROM usuario WHERE username = '$USER_REG';") == 1 ]] \
    || { echo "    ✗ el usuario del registro no está en la base que se limpia: ¿PICA_SSH apunta a otra?"; exit 1; }
pedir 202 POST /api/v1/auth/reenviar-verificacion "" "" "$(jq -nc --arg u "$USER_REG" '{email: ($u + "@example.com")}')"
pedir 400:TOKEN_INVALIDO GET /api/v1/auth/verificar "/api/v1/auth/verificar?token=no-existe"
pedir 400:CODIGO_INVALIDO POST /api/v1/auth/exchange "" "" '{"code":"no-existe"}'
pedir 200 POST /api/v1/auth/login "" "" "$(jq -nc --arg u "$ADMIN_USER" \
    '{identificador: $u, password: env.PICA_ADMIN_PASSWORD}')"
ADMIN=$(campo .accessToken)
ADMIN_REFRESH=$(campo .refreshToken)

echo
echo "Admin: roles y permisos"
pedir 200 GET /api/v1/admin/permisos "" "$ADMIN"
pedir 201 POST /api/v1/admin/roles "" "$ADMIN" "$(jq -nc --arg r "$ROL" '{nombre: $r, nombreAmigable: "Rol de la prueba de humo"}')"
ROL_ID=$(campo .id)
pedir 200 PUT /api/v1/admin/roles/{id}/permisos "/api/v1/admin/roles/$ROL_ID/permisos" "$ADMIN" '{"permisos":["USUARIO_VER"]}'
pedir 200 GET /api/v1/admin/roles "/api/v1/admin/roles?q=$ROL" "$ADMIN"
en_listado "$ROL_ID"
pedir 200 GET /api/v1/admin/roles/{id} "/api/v1/admin/roles/$ROL_ID" "$ADMIN"
pedir 200 PUT /api/v1/admin/roles/{id} "/api/v1/admin/roles/$ROL_ID" "$ADMIN" "$(jq -nc --arg r "$ROL" \
    '{nombre: $r, nombreAmigable: "Rol de la prueba de humo (editado)"}')"

echo
echo "Admin: personas"
persona() {
    jq -nc --arg d "$DNI_PER" --arg t "$1" \
        '{nombres: "Prueba", apellidos: "Humo", tipoDoc: "DNI", nroDoc: $d, fechaNacimiento: "1990-05-17", telefono: $t}'
}
pedir 201 POST /api/v1/admin/personas "" "$ADMIN" "$(persona 1100000000)"
PER_ID=$(campo .id)
pedir 200 GET /api/v1/admin/personas "/api/v1/admin/personas?q=$DNI_PER" "$ADMIN"
en_listado "$PER_ID"
pedir 200 GET /api/v1/admin/personas/{id} "/api/v1/admin/personas/$PER_ID" "$ADMIN"
pedir 200 PUT /api/v1/admin/personas/{id} "/api/v1/admin/personas/$PER_ID" "$ADMIN" "$(persona 1122222222)"

echo
echo "Admin: usuarios"
pedir 201 POST /api/v1/admin/usuarios "" "$ADMIN" "$(jq -nc \
    --arg u "$USER_ADM" --arg c "$CLAVE_1" --argjson p "$PER_ID" --argjson r "$ROL_ID" \
    '{username: $u, email: ($u + "@example.com"), passwordTemporal: $c, personaId: $p, roles: [$r]}')"
USR_ID=$(campo .id)
pedir 200 GET /api/v1/admin/usuarios "/api/v1/admin/usuarios?q=$USER_ADM" "$ADMIN"
en_listado "$USR_ID"
pedir 200 GET /api/v1/admin/usuarios/{id} "/api/v1/admin/usuarios/$USR_ID" "$ADMIN"
pedir 200 PUT /api/v1/admin/usuarios/{id} "/api/v1/admin/usuarios/$USR_ID" "$ADMIN" "$(jq -nc \
    --arg u "$USER_ADM" --argjson p "$PER_ID" \
    '{username: $u, email: ($u + "@example.com"), estado: "ACTIVO", personaId: $p, descripcion: "Prueba de humo"}')"
pedir 200 PUT /api/v1/admin/usuarios/{id}/roles "/api/v1/admin/usuarios/$USR_ID/roles" "$ADMIN" "{\"roles\":[$ROL_ID]}"
pedir 204 PUT /api/v1/admin/usuarios/{id}/password "/api/v1/admin/usuarios/$USR_ID/password" "$ADMIN" \
    "$(jq -nc --arg c "$CLAVE_2" '{password: $c}')"

echo
echo "Perfil (con el usuario de prueba)"
pedir 200 POST /api/v1/auth/login "" "" "$(jq -nc --arg u "$USER_ADM" --arg c "$CLAVE_2" '{identificador: $u, password: $c}')"
USUARIO=$(campo .accessToken)
USUARIO_REFRESH=$(campo .refreshToken)
pedir 200 GET /api/v1/me "" "$USUARIO"
pedir 200 PUT /api/v1/me "" "$USUARIO" \
    '{"nombres":"Prueba","apellidos":"Humo","fechaNacimiento":"1990-05-17","domicilioPostal":"Calle Falsa 123","telefono":"1133333333"}'
pedir 200 POST /api/v1/auth/refresh "" "" "$(jq -nc --arg t "$USUARIO_REFRESH" '{refreshToken: $t}')"
USUARIO=$(campo .accessToken)
USUARIO_REFRESH=$(campo .refreshToken)
# cierra todas sus sesiones: después de esto su access ya no sirve
pedir 204 PUT /api/v1/me/password "" "$USUARIO" "$(jq -nc --arg a "$CLAVE_2" --arg n "$CLAVE_3" \
    '{passwordActual: $a, passwordNueva: $n}')"
pedir 204 POST /api/v1/auth/logout "" "" "$(jq -nc --arg t "$USUARIO_REFRESH" '{refreshToken: $t}')"

echo
echo "Bajas y reactivaciones"
pedir 204 DELETE /api/v1/admin/usuarios/{id} "/api/v1/admin/usuarios/$USR_ID" "$ADMIN"
pedir 200 POST /api/v1/admin/usuarios/{id}/reactivar "/api/v1/admin/usuarios/$USR_ID/reactivar" "$ADMIN"
pedir 204 DELETE /api/v1/admin/usuarios/{id} "/api/v1/admin/usuarios/$USR_ID" "$ADMIN"
pedir 204 DELETE /api/v1/admin/personas/{id} "/api/v1/admin/personas/$PER_ID" "$ADMIN"
pedir 200 POST /api/v1/admin/personas/{id}/reactivar "/api/v1/admin/personas/$PER_ID/reactivar" "$ADMIN"
pedir 204 DELETE /api/v1/admin/roles/{id} "/api/v1/admin/roles/$ROL_ID" "$ADMIN"
pedir 200 POST /api/v1/admin/roles/{id}/reactivar "/api/v1/admin/roles/$ROL_ID/reactivar" "$ADMIN"
pedir 204 POST /api/v1/auth/logout "" "" "$(jq -nc --arg t "$ADMIN_REFRESH" '{refreshToken: $t}')"

# --- Cobertura: cada operación del contrato tiene que haber pasado por pedir ----------------------

mapfile -t OPERACIONES < <(awk '
    /^paths:/ { en_paths = 1; next }
    /^[^ #]/ { en_paths = 0 }
    en_paths && /^  \/[^ ]*:$/ { ruta = $1; sub(/:$/, "", ruta) }
    en_paths && /^    (get|post|put|delete|patch):/ { metodo = $1; sub(/:$/, "", metodo); print toupper(metodo) " " ruta }
' "$CONTRATO")
declare -A EN_CONTRATO=()
faltan=()
for operacion in "${OPERACIONES[@]}"; do
    EN_CONTRATO[$operacion]=1
    [[ -n ${PROBADAS[$operacion]:-} ]] || faltan+=("$operacion")
done
sobran=()
for operacion in "${!PROBADAS[@]}"; do
    [[ -n ${EN_CONTRATO[$operacion]:-} ]] || sobran+=("$operacion")
done

echo
echo "Contrato: $(( ${#OPERACIONES[@]} - ${#faltan[@]} ))/${#OPERACIONES[@]} operaciones"
for operacion in "${faltan[@]}"; do echo "  ✗ sin probar: $operacion"; done
for operacion in "${sobran[@]}"; do echo "  ✗ probada pero no está en el contrato: $operacion"; done
(( ${#faltan[@]} == 0 && ${#sobran[@]} == 0 )) || exit 1
