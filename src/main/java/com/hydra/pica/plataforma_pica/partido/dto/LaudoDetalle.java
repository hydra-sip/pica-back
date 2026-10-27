package com.hydra.pica.plataforma_pica.partido.dto;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.partido.domain.Decision;

/**
 * El laudo de una objeción en el comparativo (HU-303).
 *
 * @param resueltaPor nombre y apellido de quien laudó
 * @param evidenciaId la evidencia del partido que citó
 * @param laudoHash   SHA-256 del laudo ({@code LaudoService.sello})
 */
public record LaudoDetalle(
        Decision decision,
        String justificacion,
        String resueltaPor,
        Instant resueltaEn,
        Long evidenciaId,
        String laudoHash) {
}
