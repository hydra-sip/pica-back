package com.hydra.pica.plataforma_pica.torneo.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * La cancelación de un torneo como la ve su organizador (HU-605, T605-3). Siempre trae el pozo y el tope de gastos;
 * {@code estado} es null si el torneo no se canceló, y entonces lo demás va vacío.
 */
public record CancelacionDelTorneo(BigDecimal pozo, BigDecimal pctMaximo, BigDecimal maximoGastos, String estado,
                                   String motivo, Instant pedidaEn, List<GastoDeLaCancelacion> gastos,
                                   BigDecimal totalGastos, Instant revisadaEn, String motivoRechazo,
                                   BigDecimal reintegrado, BigDecimal retenido) {
}
