package com.hydra.pica.plataforma_pica.torneo.dto;

import java.math.BigDecimal;

import com.hydra.pica.plataforma_pica.torneo.domain.Premio;

/** Un puesto con premio: su % del pozo neto y cuánto es; {@code monto} es null si no se puede calcular. */
public record PremioDetalle(
        int puesto,
        BigDecimal pct,
        BigDecimal monto) {

    public static PremioDetalle de(Premio premio, BigDecimal monto) {
        return new PremioDetalle(premio.getId().getPuesto(), premio.getPct(), monto);
    }
}
