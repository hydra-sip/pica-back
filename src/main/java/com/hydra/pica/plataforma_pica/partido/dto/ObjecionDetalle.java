package com.hydra.pica.plataforma_pica.partido.dto;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.partido.domain.Decision;
import com.hydra.pica.plataforma_pica.partido.domain.Lado;

/**
 * La objeción de un partido, como la ven las partes (HU-302), con su laudo si ya se resolvió (HU-304).
 *
 * @param lado        el que objetó: el rival del que cargó el resultado
 * @param decision    null hasta el laudo: CONFIRMADO si quedó el resultado cargado, CORREGIDO si cambió
 * @param resueltaPor nombre y apellido de quien laudó
 * @param evidenciaId la evidencia del partido que citó el laudo
 * @param laudoHash   SHA-256 del laudo ({@code LaudoService.sello})
 */
public record ObjecionDetalle(
        Lado lado,
        String motivo,
        int puntajeLocal,
        int puntajeVisitante,
        Integer penalesLocal,
        Integer penalesVisitante,
        Instant creadaEn,
        Decision decision,
        String justificacion,
        String resueltaPor,
        Instant resueltaEn,
        Long evidenciaId,
        String laudoHash) {
}
