#!/usr/bin/env bash
# ==============================================================================
# T305-3: Prueba de RNF-TRE-01
# Requisito no funcional:
#   Dos clientes conectados concurrentemente, 100 repeticiones,
#   percentil 95 (p95) de latencia de entrega de 5 s o menos.
# Topics evaluados:
#   - /topic/torneos/{id} (cambios de bracket, pozo, estado)
#   - /topic/rifas/{id}   (venta de números, pozo)
# ==============================================================================

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$DIR"

echo "================================================================================"
echo " EJECUTANDO PRUEBA RNF-TRE-01 (T305-3)"
echo " Criterio: 2 clientes concurrentes, 100 repeticiones, p95 <= 5.0 s (5000 ms)"
echo "================================================================================"

mvn test -Dtest=RnfTre01Test

echo ""
echo ">> Prueba RNF-TRE-01 completada exitosamente."
