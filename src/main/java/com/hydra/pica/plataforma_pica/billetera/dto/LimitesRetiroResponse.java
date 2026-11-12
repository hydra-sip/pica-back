package com.hydra.pica.plataforma_pica.billetera.dto;

import java.math.BigDecimal;

/**
 * Límites de retiro configurados en la plataforma (HU-607, PICA-337).
 */
public record LimitesRetiroResponse(BigDecimal minimo, BigDecimal maximo) {
}

