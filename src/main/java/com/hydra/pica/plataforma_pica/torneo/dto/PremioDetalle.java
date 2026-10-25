package com.hydra.pica.plataforma_pica.torneo.dto;

import java.math.BigDecimal;

import com.hydra.pica.plataforma_pica.torneo.domain.Premio;

public record PremioDetalle(
        int puesto,
        BigDecimal pct) {

    public static PremioDetalle de(Premio premio) {
        return new PremioDetalle(premio.getId().getPuesto(), premio.getPct());
    }
}
