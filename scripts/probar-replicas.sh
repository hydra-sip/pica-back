#!/usr/bin/env bash
# ==============================================================================
# CE2-9: Demostración de escalado horizontal sin session affinity
# Con el stack de docker-compose.replicas.yml levantado, comprueba que con dos réplicas detrás de
# Nginx en Round Robin funcionen, cada paso atendido por las dos réplicas:
#   1. Alternancia Round Robin (header X-Handled-By)
#   2. Login local y access token aceptado por ambas réplicas
#   3. Refresh con rotación
#   4. Login con Google: el state viaja en cookie y la otra réplica lo encuentra en el callback
#   5. Canje del código de Google guardado en Postgres, de un solo uso entre réplicas
#   6. Revocación de sesión (refresh reutilizado) que corta el access token en ambas réplicas
# Cualquier chequeo que falle corta con exit 1. El login con Google completo (contra Google) se
# prueba a mano: ver README.
# ==============================================================================

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.replicas.yml}"
ADMIN_USUARIO="${ADMIN_USUARIO:-admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-Pica2026!}"

command -v curl >/dev/null 2>&1 || { echo "Se requiere curl"; exit 1; }
command -v jq >/dev/null 2>&1 || { echo "Se requiere jq"; exit 1; }

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

falla() {
    echo "  ✗ $1"
    [[ -s "$TMP/body" ]] && echo "    Respuesta: $(head -c 500 "$TMP/body")"
    exit 1
}

ok() {
    echo "  ✓ $1"
}

# pedir METODO RUTA [BODY_JSON] [ACCESS_TOKEN] [COOKIE]
# Deja STATUS, BODY, REPLICA y HEADERS de la respuesta.
pedir() {
    local metodo=$1 ruta=$2 body=${3:-} token=${4:-} cookie=${5:-}
    local args=(-s -o "$TMP/body" -D "$TMP/headers" -w '%{http_code}' -X "$metodo")
    [[ -n "$body" ]] && args+=(-H "Content-Type: application/json" -d "$body")
    [[ -n "$token" ]] && args+=(-H "Authorization: Bearer $token")
    [[ -n "$cookie" ]] && args+=(-H "Cookie: $cookie")
    STATUS=$(curl "${args[@]}" "$BASE_URL$ruta") || falla "No se pudo conectar con $BASE_URL"
    BODY=$(cat "$TMP/body")
    HEADERS=$(tr -d '\r' < "$TMP/headers")
    REPLICA=$(echo "$HEADERS" | grep -i '^x-handled-by:' | cut -d' ' -f2 || true)
    [[ -n "$REPLICA" ]] || falla "La respuesta no trae X-Handled-By: ¿está corriendo el nginx de $COMPOSE_FILE?"
}

# en_ambas DESCRIPCION STATUS_ESPERADO METODO RUTA [BODY_JSON] [ACCESS_TOKEN]
# Repite el pedido hasta que lo atiendan las dos réplicas; cada respuesta tiene que dar el status esperado.
en_ambas() {
    local descripcion=$1 esperado=$2
    shift 2
    local vistas=""
    for _ in 1 2 3 4; do
        pedir "$@"
        [[ "$STATUS" == "$esperado" ]] || falla "$descripcion: $REPLICA respondió $STATUS (se esperaba $esperado)"
        [[ "$vistas" == *"$REPLICA"* ]] || vistas="$vistas $REPLICA"
        [[ $(wc -w <<< "$vistas") -ge 2 ]] && break
    done
    [[ $(wc -w <<< "$vistas") -ge 2 ]] || falla "$descripcion: solo respondió$vistas"
    ok "$descripcion: $esperado en$vistas"
}

echo "======================================================================"
echo "Demostración CE2-9: Escalado Horizontal sin Session Affinity"
echo "Target: $BASE_URL"
echo "======================================================================"

echo
echo "[1/6] Round Robin en /actuator/health..."
REPLICAS=""
for i in 1 2 3 4; do
    pedir GET /actuator/health
    [[ "$STATUS" == 200 ]] || falla "Health respondió $STATUS en $REPLICA"
    echo "    Petición $i -> $REPLICA"
    [[ "$REPLICAS" == *"$REPLICA"* ]] || REPLICAS="$REPLICAS $REPLICA"
done
[[ $(wc -w <<< "$REPLICAS") -ge 2 ]] || falla "Todas las peticiones las atendió$REPLICAS: no hay Round Robin"
ok "Las peticiones se reparten entre$REPLICAS"

echo
echo "[2/6] Login local..."
pedir POST /api/v1/auth/login "$(jq -nc --arg u "$ADMIN_USUARIO" --arg p "$ADMIN_PASSWORD" \
    '{identificador: $u, password: $p}')"
[[ "$STATUS" == 200 ]] || falla "Login respondió $STATUS"
ACCESS_TOKEN=$(jq -r '.accessToken // empty' <<< "$BODY")
REFRESH_TOKEN=$(jq -r '.refreshToken // empty' <<< "$BODY")
[[ -n "$ACCESS_TOKEN" && -n "$REFRESH_TOKEN" ]] || falla "El login no devolvió el par de tokens"
ok "Login en $REPLICA"
en_ambas "GET /me con el access token del login" 200 GET /api/v1/me "" "$ACCESS_TOKEN"

echo
echo "[3/6] Refresh con rotación..."
pedir POST /api/v1/auth/refresh "$(jq -nc --arg t "$REFRESH_TOKEN" '{refreshToken: $t}')"
[[ "$STATUS" == 200 ]] || falla "Refresh respondió $STATUS"
NUEVO_ACCESS_TOKEN=$(jq -r '.accessToken // empty' <<< "$BODY")
NUEVO_REFRESH_TOKEN=$(jq -r '.refreshToken // empty' <<< "$BODY")
[[ -n "$NUEVO_ACCESS_TOKEN" && -n "$NUEVO_REFRESH_TOKEN" ]] || falla "El refresh no devolvió el par de tokens"
[[ "$NUEVO_REFRESH_TOKEN" != "$REFRESH_TOKEN" ]] || falla "El refresh devolvió el mismo refresh token"
ok "Refresh en $REPLICA"
en_ambas "GET /me con el access token del refresh" 200 GET /api/v1/me "" "$NUEVO_ACCESS_TOKEN"

echo
echo "[4/6] Login con Google: state en cookie..."
pedir GET /oauth2/authorization/google
[[ "$STATUS" == 302 ]] || falla "/oauth2/authorization/google respondió $STATUS"
REPLICA_INICIO=$REPLICA
LOCATION=$(echo "$HEADERS" | grep -i '^location:' | cut -d' ' -f2-)
[[ "$LOCATION" == https://accounts.google.com/* ]] || falla "No redirige a Google: $LOCATION"
VUELTA="$BASE_URL/login/oauth2/code/google"
VUELTA_CODIFICADA=$(jq -rn --arg v "$VUELTA" '$v|@uri')
[[ "$LOCATION" == *"redirect_uri=$VUELTA"* || "$LOCATION" == *"redirect_uri=$VUELTA_CODIFICADA"* ]] \
    || falla "El redirect_uri no es $VUELTA: $LOCATION"
ok "Redirige a Google con redirect_uri=$VUELTA ($REPLICA_INICIO)"
COOKIE=$(echo "$HEADERS" | grep -i '^set-cookie: oauth2_auth_request=' | head -n 1 | cut -d' ' -f2 | cut -d';' -f1)
[[ -n "$COOKIE" ]] || falla "No se guardó el authorization request en la cookie oauth2_auth_request"
echo "$HEADERS" | grep -qi '^set-cookie: JSESSIONID=' && falla "Se creó una HttpSession (JSESSIONID)"
ok "Authorization request en cookie, sin JSESSIONID"
STATE=$(sed -n 's/.*[?&]state=\([^&]*\).*/\1/p' <<< "$LOCATION")

# El callback con un code inventado falla igual contra Google, pero si la réplica encontró el request
# en la cookie la borra (Max-Age=0); sin el request no hay nada que borrar.
for _ in 1 2 3; do
    pedir GET "/login/oauth2/code/google?code=code-de-prueba&state=$STATE" "" "" "$COOKIE"
    [[ "$REPLICA" != "$REPLICA_INICIO" ]] && break
done
[[ "$REPLICA" != "$REPLICA_INICIO" ]] || falla "El callback lo atendió siempre la misma réplica que la redirección"
echo "$HEADERS" | grep -i '^set-cookie: oauth2_auth_request=;' | grep -qi 'max-age=0' \
    || falla "$REPLICA no encontró el authorization request de la cookie en el callback"
ok "El callback en $REPLICA encontró el request que guardó $REPLICA_INICIO"

echo
echo "[5/6] Canje del código de Google en Postgres..."
# Sin Google de verdad no hay quién emita el código: se inserta igual que lo haría OAuthCodeStore
CODIGO="prueba-$(date +%s)-$RANDOM$RANDOM$RANDOM"
if command -v sha256sum >/dev/null 2>&1; then
    HASH=$(printf '%s' "$CODIGO" | sha256sum | cut -d' ' -f1)
else
    HASH=$(printf '%s' "$CODIGO" | shasum -a 256 | cut -d' ' -f1)
fi
docker compose -f "$COMPOSE_FILE" exec -T db psql -q -v ON_ERROR_STOP=1 -U postgres -d tournament_db -c \
    "INSERT INTO codigo_canje_oauth (codigo_hash, usuario_id, vence_en)
     SELECT '$HASH', id, now() + interval '60 seconds' FROM usuario WHERE username = '$ADMIN_USUARIO'" \
    >/dev/null || falla "No se pudo insertar el código en la base"
pedir POST /api/v1/auth/exchange "$(jq -nc --arg c "$CODIGO" '{code: $c}')"
[[ "$STATUS" == 200 ]] || falla "El canje respondió $STATUS en $REPLICA"
jq -e '.accessToken and .refreshToken' <<< "$BODY" >/dev/null || falla "El canje no devolvió el par de tokens"
REPLICA_CANJE=$REPLICA
ok "Canje en $REPLICA_CANJE"
pedir POST /api/v1/auth/exchange "$(jq -nc --arg c "$CODIGO" '{code: $c}')"
[[ "$REPLICA" != "$REPLICA_CANJE" ]] || falla "El segundo canje lo atendió la misma réplica"
[[ "$STATUS" == 400 ]] && grep -q CODIGO_INVALIDO <<< "$BODY" \
    || falla "El segundo canje en $REPLICA respondió $STATUS (se esperaba 400 CODIGO_INVALIDO)"
ok "El mismo código en $REPLICA: 400 CODIGO_INVALIDO (un solo uso)"

echo
echo "[6/6] Revocación de sesión..."
# Reusar un refresh ya rotado sube la versión de sesión y corta todos los access tokens (CE2-2)
pedir POST /api/v1/auth/refresh "$(jq -nc --arg t "$REFRESH_TOKEN" '{refreshToken: $t}')"
[[ "$STATUS" == 401 ]] || falla "Reusar el refresh rotado respondió $STATUS (se esperaba 401)"
ok "Refresh reutilizado rechazado en $REPLICA"
en_ambas "GET /me con el access token ya revocado" 401 GET /api/v1/me "" "$NUEVO_ACCESS_TOKEN"
grep -q SESION_REVOCADA <<< "$BODY" || falla "El 401 no es SESION_REVOCADA"
ok "Las dos réplicas responden SESION_REVOCADA"
pedir POST /api/v1/auth/refresh "$(jq -nc --arg t "$NUEVO_REFRESH_TOKEN" '{refreshToken: $t}')"
[[ "$STATUS" == 401 ]] || falla "El refresh vigente respondió $STATUS después de la revocación (se esperaba 401)"
ok "El refresh vigente también quedó revocado ($REPLICA)"

echo
echo "======================================================================"
echo "Todo en orden: la API escala horizontalmente sin session affinity."
echo "======================================================================"
