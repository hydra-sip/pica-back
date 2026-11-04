#!/usr/bin/env bash
# Revisa las migraciones de Flyway que trae una rama contra la rama base (PICA-294). Lo corre el CI en
# cada PR y, al mergear a dev, en cada PR abierto (.github/workflows/migraciones.yml).
#
#   scripts/revisar-migraciones.sh origin/dev HEAD
#
# Falla si la rama:
#   - agrega una migración que no se llama V<n>__descripcion.sql (Flyway la ignora sin avisar);
#   - agrega versiones que no siguen a la última de la base: V<última+1>, V<última+2>... sin repetir ni saltear;
#   - edita, renombra o borra una migración que ya está en la base (nunca se edita una mergeada).
#
# Compara contra la punta actual de la base, no contra el punto donde salió la rama: así un PR que quedó
# atrás después de que otro mergeó la misma versión también falla.

set -euo pipefail

BASE=${1:?uso: $0 <ref-base> [<ref-rama>]}
RAMA=${2:-HEAD}
DIR=src/main/resources/db/migration
NOMBRE='^V([0-9]+)__[A-Za-z0-9_]+\.sql$'

errores=()

version() { [[ $1 =~ ^V([0-9]+)__ ]] && echo $((10#${BASH_REMATCH[1]})); }

# archivo -> blob de las migraciones de un commit
listar() { git ls-tree "$1" -- "$DIR/" | while read -r _ _ blob ruta; do echo "${ruta##*/} $blob"; done; }

declare -A base rama al_salir en_base
while read -r archivo blob; do base[$archivo]=$blob; done < <(listar "$BASE")
while read -r archivo blob; do rama[$archivo]=$blob; done < <(listar "$RAMA")
# Lo que la rama tenía al salir de la base. Entre dev y main, que se pasan con squash, el punto en común es
# anterior a las migraciones y queda vacío: ahí solo se ven las nuevas y las editadas.
desde=$(git merge-base "$BASE" "$RAMA" || true)
if [[ -n $desde ]]; then
    while read -r archivo blob; do al_salir[$archivo]=$blob; done < <(listar "$desde")
fi

# La última versión de la base y qué archivo tiene cada una
ultima=0
for archivo in "${!base[@]}"; do
    v=$(version "$archivo") || continue
    en_base[$v]=$archivo
    ((v > ultima)) && ultima=$v
done

mergeada="ya está mergeada y no se puede editar, renombrar ni borrar; el cambio va en una migración nueva"
nuevas=()
for archivo in "${!rama[@]}"; do
    if [[ -z ${base[$archivo]:-} ]]; then
        if [[ ! $archivo =~ $NOMBRE ]]; then
            errores+=("$archivo: el nombre tiene que ser V<n>__descripcion.sql (con dos guiones bajos); si no, Flyway la ignora")
        else
            nuevas+=("$(version "$archivo") $archivo")
        fi
    elif [[ ${base[$archivo]} != "${rama[$archivo]}" ]]; then
        errores+=("$archivo: $mergeada")
    fi
done
# Borrada o renombrada: la rama la tenía al salir, sigue en la base y ya no está
for archivo in "${!al_salir[@]}"; do
    if [[ -n ${base[$archivo]:-} && -z ${rama[$archivo]:-} ]]; then
        errores+=("$archivo: $mergeada")
    fi
done

esperada=$((ultima + 1))
while read -r v archivo; do
    [[ -z ${v:-} ]] && continue
    if [[ -n ${en_base[$v]:-} ]]; then
        errores+=("$archivo: la V$v ya existe en la base ($BASE: ${en_base[$v]}); renombrala a V$esperada")
    elif ((v != esperada)); then
        errores+=("$archivo: después de V$((esperada - 1)) sigue V$esperada; renombrala")
    fi
    esperada=$((esperada + 1))
done < <(printf '%s\n' "${nuevas[@]+"${nuevas[@]}"}" | sort -n)

if ((${#errores[@]})); then
    printf '%s\n' "${errores[@]}" | sort -V
    exit 1
fi

if ((${#nuevas[@]} == 1)); then
    echo "Migración en orden: V$((ultima + 1)) sigue a la V$ultima de $BASE"
elif ((${#nuevas[@]})); then
    echo "Migraciones en orden: V$((ultima + 1)) a V$((esperada - 1)) siguen a la V$ultima de $BASE"
else
    echo "Sin migraciones nuevas"
fi
