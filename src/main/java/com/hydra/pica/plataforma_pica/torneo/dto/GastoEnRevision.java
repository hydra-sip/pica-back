package com.hydra.pica.plataforma_pica.torneo.dto;

import java.math.BigDecimal;

import com.hydra.pica.plataforma_pica.torneo.domain.GastoCancelacion;

/** Un gasto de una cancelación en revisión (HU-605); el archivo se baja aparte. */
public record GastoEnRevision(Long id, String concepto, BigDecimal monto, String tipoContenido) {

    public static GastoEnRevision de(GastoCancelacion gasto) {
        return new GastoEnRevision(gasto.getId(), gasto.getConcepto(), gasto.getMonto(), gasto.getTipoContenido());
    }
}
