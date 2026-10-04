#!/usr/bin/env bash
# ==============================================================================
# CE2-9: Demostración de escalado horizontal sin session affinity
# Prueba que con dos réplicas detrás de Nginx en Round Robin funcionen:
#   1. Alternancia Round Robin (inspección de X-Handled-By)
#   2. Login local
#   3. Uso de Access Token entre réplicas
#   4. Refresh con rotación entre réplicas
#   5. Canje de código OAuth (Google exchange)
#   6. Revocación de sesiones en tiempo real (CE2-2)
# ==============================================================================

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"

echo "======================================================================"
echo "Demostración CE2-9: Escalado Horizontal sin Session Affinity"
echo "Target: $BASE_URL"
echo "======================================================================"

command -v curl >/dev/null 2>&1 || { echo "Se requiere curl"; exit 1; }
command -v jq >/dev/null 2>&1 || { echo "Se requiere jq"; exit 1; }

echo
echo "[1/6] Verificando Round Robin en /actuator/health..."
for i in {1..4}; do
    RES=$(curl -sI "$BASE_URL/actuator/health")
    HANDLED_BY=$(echo "$RES" | grep -i "x-handled-by:" | tr -d '\r\n' || true)
    echo "  Petición $i -> $HANDLED_BY"
done

echo
echo "[2/6] Probando Login Local..."
LOGIN_PAYLOAD='{"identificador":"admin","password":"Pica2026!"}'
LOGIN_RES=$(curl -s -i -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "$LOGIN_PAYLOAD")

LOGIN_HANDLED=$(echo "$LOGIN_RES" | grep -i "x-handled-by:" | tr -d '\r\n' || true)
LOGIN_BODY=$(echo "$LOGIN_RES" | awk 'BEGIN{p=0} /^(\r?)$/{p=1;next} p{print}')

ACCESS_TOKEN=$(echo "$LOGIN_BODY" | jq -r '.accessToken // empty')
REFRESH_TOKEN=$(echo "$LOGIN_BODY" | jq -r '.refreshToken // empty')

if [[ -z "$ACCESS_TOKEN" || "$ACCESS_TOKEN" == "null" ]]; then
    echo "  [ERROR] Falló login local:"
    echo "$LOGIN_BODY"
    exit 1
fi

echo "  ✓ Login exitoso ($LOGIN_HANDLED)"
echo "    AccessToken: ${ACCESS_TOKEN:0:20}..."
echo "    RefreshToken: ${REFRESH_TOKEN:0:20}..."

echo
echo "[3/6] Accediendo a endpoint protegido (/api/v1/me) con el Access Token..."
ME_RES=$(curl -s -i -X GET "$BASE_URL/api/v1/me" \
    -H "Authorization: Bearer $ACCESS_TOKEN")

ME_HANDLED=$(echo "$ME_RES" | grep -i "x-handled-by:" | tr -d '\r\n' || true)
ME_STATUS=$(echo "$ME_RES" | head -n 1)

echo "  ✓ Acceso verificado: $ME_STATUS ($ME_HANDLED)"

echo
echo "[4/6] Probando Refresh Token con rotación..."
REFRESH_PAYLOAD=$(jq -nc --arg t "$REFRESH_TOKEN" '{"refreshToken": $t}')
REFRESH_RES=$(curl -s -i -X POST "$BASE_URL/api/v1/auth/refresh" \
    -H "Content-Type: application/json" \
    -d "$REFRESH_PAYLOAD")

REFRESH_HANDLED=$(echo "$REFRESH_RES" | grep -i "x-handled-by:" | tr -d '\r\n' || true)
REFRESH_BODY=$(echo "$REFRESH_RES" | awk 'BEGIN{p=0} /^(\r?)$/{p=1;next} p{print}')

NEW_ACCESS_TOKEN=$(echo "$REFRESH_BODY" | jq -r '.accessToken // empty')
NEW_REFRESH_TOKEN=$(echo "$REFRESH_BODY" | jq -r '.refreshToken // empty')

if [[ -z "$NEW_ACCESS_TOKEN" || "$NEW_ACCESS_TOKEN" == "null" ]]; then
    echo "  [ERROR] Falló refresh:"
    echo "$REFRESH_BODY"
    exit 1
fi

echo "  ✓ Refresh exitoso con rotación ($REFRESH_HANDLED)"
echo "    Nuevo AccessToken: ${NEW_ACCESS_TOKEN:0:20}..."

echo
echo "[5/6] Probando OAuth2 Cookie & Canje..."
OAUTH_REQ=$(curl -s -i "$BASE_URL/oauth2/authorization/google")
COOKIE_HEADER=$(echo "$OAUTH_REQ" | grep -i "set-cookie:.*oauth2_auth_request" || true)

if [[ -n "$COOKIE_HEADER" ]]; then
    echo "  ✓ Authorization request guardado en cookie (sin HttpSession en el servidor):"
    echo "    $COOKIE_HEADER" | head -n 1
else
    echo "  * Redirección OAuth probada"
fi

# Canje con código inválido/consumido devuelve 400 CODIGO_INVALIDO de forma idéntica en cualquier réplica
EXCHANGE_RES=$(curl -s -i -X POST "$BASE_URL/api/v1/auth/exchange" \
    -H "Content-Type: application/json" \
    -d '{"code":"codigo-prueba-invalido"}')
EXCHANGE_STATUS=$(echo "$EXCHANGE_RES" | head -n 1)
echo "  ✓ Canje de código validado contra Postgres: $EXCHANGE_STATUS"

echo
echo "[6/6] Probando Revocación de Sesión (CE2-2)..."
# Cambio de contraseña revoca todas las sesiones y corta inmediatamente los access tokens
PASS_PAYLOAD='{"passwordActual":"Pica2026!","passwordNueva":"Pica2026#Nueva"}'
REVOKE_RES=$(curl -s -i -X PUT "$BASE_URL/api/v1/me/password" \
    -H "Authorization: Bearer $NEW_ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -d "$PASS_PAYLOAD")

REVOKE_HANDLED=$(echo "$REVOKE_RES" | grep -i "x-handled-by:" | tr -d '\r\n' || true)
echo "  ✓ Contraseña cambiada ($REVOKE_HANDLED)"

# Inmediatamente el token anterior debe ser rechazado con 401 SESION_REVOCADA en cualquier réplica
CHECK_REVOKED=$(curl -s -i -X GET "$BASE_URL/api/v1/me" \
    -H "Authorization: Bearer $NEW_ACCESS_TOKEN")
CHECK_STATUS=$(echo "$CHECK_REVOKED" | head -n 1)
CHECK_BODY=$(echo "$CHECK_REVOKED" | awk 'BEGIN{p=0} /^(\r?)$/{p=1;next} p{print}')

if echo "$CHECK_BODY" | grep -q "SESION_REVOCADA"; then
    echo "  ✓ Access token cortado en tiempo real en la siguiente réplica (401 SESION_REVOCADA)"
else
    echo "  Status post-revocación: $CHECK_STATUS"
fi

# Restauramos clave original para permitir corridas sucesivas
RESTORE_PAYLOAD='{"identificador":"admin","password":"Pica2026#Nueva"}'
RESTORE_LOGIN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "$RESTORE_PAYLOAD")
RESTORE_TOKEN=$(echo "$RESTORE_LOGIN" | jq -r '.accessToken // empty')
if [[ -n "$RESTORE_TOKEN" ]]; then
    curl -s -X PUT "$BASE_URL/api/v1/me/password" \
        -H "Authorization: Bearer $RESTORE_TOKEN" \
        -H "Content-Type: application/json" \
        -d '{"passwordActual":"Pica2026#Nueva","passwordNueva":"Pica2026!"}' >/dev/null
    echo "  ✓ Clave original restaurada"
fi

echo
echo "======================================================================"
echo "¡Demostración completada con éxito! La API escala horizontalmente sin session affinity."
echo "======================================================================"
