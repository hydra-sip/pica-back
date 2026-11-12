package com.hydra.pica.plataforma_pica.torneo.dto;

import java.math.BigDecimal;

import com.hydra.pica.plataforma_pica.torneo.domain.EstadoPagoGasto;

/**
 * Los honorarios que el organizador depositó para el árbitro en un torneo (HU-604, wireframe 49).
 *
 * @param estado EN_CUSTODIA hasta la liquidación, ACREDITADO después o REINTEGRADO si el torneo se canceló
 */
public record HonorarioDesignacion(BigDecimal monto, String moneda, EstadoPagoGasto estado) {
}
