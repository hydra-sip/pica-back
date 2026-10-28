package com.hydra.pica.plataforma_pica.torneo.dto;

import java.math.BigDecimal;
import java.util.List;

import com.hydra.pica.plataforma_pica.torneo.domain.GastosAdministrativos;
import com.hydra.pica.plataforma_pica.torneo.domain.Premio;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;

/**
 * El esquema económico del paso 3 (HU-601, PICA-203). La inscripción va entera a la bolsa del torneo; quien se
 * inscribe paga aparte los gastos administrativos de la plataforma (PICA-283).
 *
 * @param pctGastosAdministrativos el que quedó sellado al publicar o, en BORRADOR, el vigente de la plataforma
 * @param gastosAdministrativos lo que se paga de gastos por cada inscripción; null sin valor de inscripción
 * @param totalAPagar valor de la inscripción más gastos; null sin valor de inscripción
 * @param premios el reparto del pozo de competidores por puesto, en orden
 */
public record EconomiaTorneo(
        BigDecimal valorInscripcion,
        String moneda,
        BigDecimal pctGastosAdministrativos,
        BigDecimal gastosAdministrativos,
        BigDecimal totalAPagar,
        List<PremioPuesto> premios) {

    public record PremioPuesto(int puesto, BigDecimal pct) {
    }

    /** {@code pctGastosAdministrativos}: el del torneo si ya lo tiene, si no el vigente. */
    public static EconomiaTorneo de(Torneo t, List<Premio> premios, BigDecimal pctGastosAdministrativos) {
        return new EconomiaTorneo(t.getValorInscripcion(), t.getMoneda(), pctGastosAdministrativos,
                GastosAdministrativos.monto(t.getValorInscripcion(), pctGastosAdministrativos),
                GastosAdministrativos.total(t.getValorInscripcion(), pctGastosAdministrativos),
                premios.stream().map(p -> new PremioPuesto(p.getId().getPuesto(), p.getPct())).toList());
    }
}
