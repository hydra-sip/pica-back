package com.hydra.pica.plataforma_pica.torneo.dto;

import java.math.BigDecimal;
import java.util.List;

import com.hydra.pica.plataforma_pica.torneo.domain.Premio;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;

/**
 * El esquema económico del paso 3 (HU-601, PICA-203). Lo recaudado (inscripciones y la rifa del torneo) es una sola
 * bolsa que se reparte en tres: el pozo de competidores, el premio de la rifa y la comisión de la plataforma, que
 * suman 100. La comisión no se guarda: es lo que queda.
 *
 * @param pctComision 100 menos los otros dos; null mientras falte alguno
 * @param premios el reparto del pozo de competidores por puesto, en orden
 */
public record EconomiaTorneo(
        BigDecimal valorInscripcion,
        String moneda,
        BigDecimal pctPozoCompetidores,
        BigDecimal pctPremioRifa,
        BigDecimal pctComision,
        List<PremioPuesto> premios) {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    public record PremioPuesto(int puesto, BigDecimal pct) {
    }

    public static EconomiaTorneo de(Torneo t, List<Premio> premios) {
        return new EconomiaTorneo(t.getValorInscripcion(), t.getMoneda(), t.getPctPozoCompetidores(),
                t.getPctPremioRifa(), comision(t.getPctPozoCompetidores(), t.getPctPremioRifa()),
                premios.stream().map(p -> new PremioPuesto(p.getId().getPuesto(), p.getPct())).toList());
    }

    public static BigDecimal comision(BigDecimal pctPozoCompetidores, BigDecimal pctPremioRifa) {
        if (pctPozoCompetidores == null || pctPremioRifa == null) {
            return null;
        }
        return CIEN.subtract(pctPozoCompetidores).subtract(pctPremioRifa);
    }
}
