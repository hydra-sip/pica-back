package com.hydra.pica.plataforma_pica.partido.dto;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.partido.domain.Lado;

/**
 * La objeción de un partido, como la ven las partes (HU-302).
 *
 * @param lado el que objetó: el rival del que cargó el resultado
 */
public record ObjecionDetalle(
        Lado lado,
        String motivo,
        int puntajeLocal,
        int puntajeVisitante,
        Integer penalesLocal,
        Integer penalesVisitante,
        Instant creadaEn) {
}
