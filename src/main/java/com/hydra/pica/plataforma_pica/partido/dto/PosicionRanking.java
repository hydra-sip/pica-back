package com.hydra.pica.plataforma_pica.partido.dto;

import java.time.Instant;

/**
 * Una fila de posición en el ranking de pronósticos (HU-307, T307-1).
 *
 * @param posicion              posición obtenida en el ranking (1, 2, 3...)
 * @param usuarioId             identificador del usuario
 * @param username              nombre de usuario
 * @param nombre                nombre y apellido (o username si no tiene persona)
 * @param puntos                puntaje acumulado obtenido
 * @param aciertos              cantidad de pronósticos acertados (puntos > 0)
 * @param pronosticosTotales    cantidad total de pronósticos registrados
 * @param primerPronosticoEn    fecha del primer pronóstico registrado (criterio de desempate)
 */
public record PosicionRanking(
        int posicion,
        Long usuarioId,
        String username,
        String nombre,
        int puntos,
        int aciertos,
        int pronosticosTotales,
        Instant primerPronosticoEn) {
}
