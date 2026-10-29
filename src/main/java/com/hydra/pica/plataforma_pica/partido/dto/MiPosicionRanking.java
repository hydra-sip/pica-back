package com.hydra.pica.plataforma_pica.partido.dto;

/**
 * Posición del usuario autenticado en el ranking (HU-307, T307-1).
 * Si no ha realizado pronósticos, los valores numéricos son null y se incluye un mensaje descriptivo.
 *
 * @param posicion posición actual en la tabla (null si no ha realizado pronósticos)
 * @param puntos   puntaje acumulado (null si no ha realizado pronósticos)
 * @param aciertos cantidad de aciertos (null si no ha realizado pronósticos)
 * @param mensaje  mensaje informativo para usuarios sin pronósticos registrados
 */
public record MiPosicionRanking(
        Integer posicion,
        Integer puntos,
        Integer aciertos,
        String mensaje) {
}
