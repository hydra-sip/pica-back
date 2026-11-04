package com.hydra.pica.plataforma_pica.user.repository;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

/**
 * Búsqueda por texto sin distinguir mayúsculas ni tildes: "perez", "PEREZ" y "Pérez" encuentran a Pérez.
 * La columna se normaliza en SQL con translate() y el texto buscado con la misma tabla en Java, así
 * los dos lados quedan iguales sin depender de la extensión unaccent.
 */
public final class BusquedaSinTildes {

    private static final String CON_TILDE = "áàäâãéèëêíìïîóòöôõúùüûñçÁÀÄÂÃÉÈËÊÍÌÏÎÓÒÖÔÕÚÙÜÛÑÇ";
    private static final String SIN_TILDE = "aaaaaeeeeiiiiooooouuuuncaaaaaeeeeiiiiooooouuuunc";
    // el de Postgres por defecto; patron() no escapa y sigue andando igual con él
    private static final char ESCAPE = '\\';

    private BusquedaSinTildes() {
    }

    /** Patrón para {@code LIKE}: normalizado y con comodines; null si no hay nada que buscar. */
    static String patron(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        return "%" + normalizar(texto.trim()) + "%";
    }

    /**
     * Un patrón por palabra, para exigir que aparezcan todas: "juan perez" encuentra a Juan Pérez.
     * % y _ se escapan: "%%%" no tiene que traer a todos.
     */
    public static List<String> patronesPorPalabra(String texto) {
        if (texto == null || texto.isBlank()) {
            return List.of();
        }
        return Arrays.stream(texto.strip().split("\\s+"))
                .map(palabra -> "%" + escaparComodines(normalizar(palabra)) + "%")
                .toList();
    }

    private static String escaparComodines(String texto) {
        return texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String normalizar(String texto) {
        StringBuilder normalizado = new StringBuilder();
        for (char letra : texto.toLowerCase(Locale.ROOT).toCharArray()) {
            int posicion = CON_TILDE.indexOf(letra);
            normalizado.append(posicion < 0 ? letra : SIN_TILDE.charAt(posicion));
        }
        return normalizado.toString();
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

    /** Alguna de las columnas, normalizada, contiene el patrón. */
    public static Predicate algunaContiene(CriteriaBuilder cb, String patron, List<Expression<String>> columnas) {
        return cb.or(columnas.stream()
                .map(columna -> cb.like(criteria(cb, columna), patron, ESCAPE))
                .toArray(Predicate[]::new));
    }
}
