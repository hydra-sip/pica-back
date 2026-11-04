package com.hydra.pica.plataforma_pica.partido.dto;

import java.time.Instant;

/**
 * Pronóstico propio del usuario autenticado en un partido (HU-306, T306-1).
 */
public record MiPronostico(
        String eleccion,
        Integer puntos,
        Instant creadoEn,
        Instant modificadoEn) {
}
