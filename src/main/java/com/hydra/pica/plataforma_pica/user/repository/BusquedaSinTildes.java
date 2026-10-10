package com.hydra.pica.plataforma_pica.user.repository;

import java.util.Locale;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;

/**
 * Búsqueda por texto sin distinguir mayúsculas ni tildes: "perez", "PEREZ" y "Pérez" encuentran a Pérez.
 * La columna se normaliza en SQL con translate() y el texto buscado con la misma tabla en Java, así
 * los dos lados quedan iguales sin depender de la extensión unaccent.
 */
final class BusquedaSinTildes {

    private static final String CON_TILDE = "áàäâãéèëêíìïîóòöôõúùüûñçÁÀÄÂÃÉÈËÊÍÌÏÎÓÒÖÔÕÚÙÜÛÑÇ";
    private static final String SIN_TILDE = "aaaaaeeeeiiiiooooouuuuncaaaaaeeeeiiiiooooouuuunc";

    private BusquedaSinTildes() {
    }

    /** Patrón para {@code LIKE}: normalizado y con comodines; null si no hay nada que buscar. */
    static String patron(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        String normalizado = texto.trim().toLowerCase(Locale.ROOT);
        StringBuilder patron = new StringBuilder("%");
        for (char letra : normalizado.toCharArray()) {
            int posicion = CON_TILDE.indexOf(letra);
            patron.append(posicion < 0 ? letra : SIN_TILDE.charAt(posicion));
        }
        return patron.append('%').toString();
    }

    /** La columna normalizada, para SQL nativo. */
    static String sql(String columna) {
        return "translate(lower(" + columna + "), '" + CON_TILDE + "', '" + SIN_TILDE + "')";
    }

    /** La columna normalizada, para Criteria. */
    static Expression<String> criteria(CriteriaBuilder cb, Expression<String> columna) {
        return cb.function("translate", String.class,
                cb.lower(columna), cb.literal(CON_TILDE), cb.literal(SIN_TILDE));
    }
}
