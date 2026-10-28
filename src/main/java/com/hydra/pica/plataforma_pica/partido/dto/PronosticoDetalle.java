package com.hydra.pica.plataforma_pica.partido.dto;

import java.time.Instant;

/**
 * Detalle de un pronóstico registrado (HU-306, T306-1).
 *
 * @param partidoId     identificador del partido
 * @param usuarioId     identificador del usuario pronosticador
 * @param eleccion      "1", "X" o "2"
 * @param puntos        puntos obtenidos (null si el partido aún no finalizó, >= 0 si ya fue calculado)
 * @param creadoEn      fecha de registro inicial
 * @param modificadoEn  fecha de última modificación
 */
public record PronosticoDetalle(
        Long partidoId,
        Long usuarioId,
        String eleccion,
        Integer puntos,
        Instant creadoEn,
        Instant modificadoEn) {
}
