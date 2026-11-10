package com.hydra.pica.plataforma_pica.torneo.dto;

import java.math.BigDecimal;

/**
 * Un gasto que el organizador ya pagó y quiere retener del pozo al cancelar (HU-605), con el contenido de su comprobante
 * (PDF, JPG o PNG); null si no lo adjuntó.
 */
public record GastoDeclarado(BigDecimal monto, String concepto, byte[] comprobante) {
}
