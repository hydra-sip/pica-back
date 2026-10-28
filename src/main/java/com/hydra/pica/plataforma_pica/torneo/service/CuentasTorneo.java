package com.hydra.pica.plataforma_pica.torneo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;

/**
 * Las cuentas del esquema económico (HU-601) para una cantidad de participantes: recaudación = inscripción ×
 * participantes, que va entera al pozo de competidores (los gastos administrativos se pagan aparte, PICA-283), y el
 * pozo neto es la recaudación menos los gastos previstos. Las usan el resumen del paso 3 y el catálogo público, así dan
 * el mismo número. La rifa todavía no suma al pozo (HU-405). Lo que no se puede calcular porque falta un dato queda en
 * null.
 *
 * @param pozoNeto puede quedar negativo si los gastos superan el pozo; publicar lo rechaza
 */
public record CuentasTorneo(
        BigDecimal recaudacion,
        BigDecimal gastos,
        BigDecimal pozoNeto) {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    static CuentasTorneo de(Torneo torneo, Integer participantes, BigDecimal gastos) {
        BigDecimal recaudacion = torneo.getValorInscripcion() == null || participantes == null ? null
                : torneo.getValorInscripcion().multiply(BigDecimal.valueOf(participantes));
        return new CuentasTorneo(recaudacion, gastos, recaudacion == null ? null : recaudacion.subtract(gastos));
    }

    /** Lo que le toca a un puesto del pozo neto. */
    BigDecimal delPuesto(BigDecimal pct) {
        return pozoNeto == null || pct == null ? null : pozoNeto.multiply(pct).divide(CIEN, 2, RoundingMode.HALF_UP);
    }
}
