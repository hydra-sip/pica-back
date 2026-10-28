package com.hydra.pica.plataforma_pica.partido.dto;

import java.time.Instant;

/**
 * Proyección de agregación de estadísticas de pronósticos por usuario (HU-307, T307-1).
 */
public record EstadisticaPronosticosUsuario(
        Long usuarioId,
        Long puntos,
        Long aciertos,
        Long totalPronosticos,
        Instant primerPronosticoEn) {
}
