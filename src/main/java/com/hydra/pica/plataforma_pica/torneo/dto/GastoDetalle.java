package com.hydra.pica.plataforma_pica.torneo.dto;

import java.math.BigDecimal;

import com.hydra.pica.plataforma_pica.torneo.domain.Gasto;

/** Un gasto previsto; {@code gastoId} lo numera dentro del torneo. */
public record GastoDetalle(int gastoId, String descripcion, BigDecimal monto) {

    public static GastoDetalle de(Gasto gasto) {
        return new GastoDetalle(gasto.getId().getGastoId(), gasto.getDescripcion(), gasto.getMonto());
    }
}
