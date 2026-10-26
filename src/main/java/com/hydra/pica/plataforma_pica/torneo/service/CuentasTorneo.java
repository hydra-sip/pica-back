package com.hydra.pica.plataforma_pica.torneo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.dto.EconomiaTorneo;

/**
 * Las cuentas del esquema económico (HU-601) para una cantidad de participantes: recaudación = inscripción ×
 * participantes; de ahí la comisión, el premio de la rifa y el pozo de competidores por sus %, y el pozo neto es el de
 * competidores menos los gastos. Las usan el resumen del paso 3 y el catálogo público, así dan el mismo número. La
 * rifa todavía no suma a la bolsa. Lo que no se puede calcular porque falta un dato queda en null.
 *
 * @param pozoNeto puede quedar negativo si los gastos superan el pozo; publicar lo rechaza
 */
record CuentasTorneo(
        BigDecimal recaudacion,
        BigDecimal comision,
        BigDecimal premioRifa,
        BigDecimal pozoCompetidores,
        BigDecimal gastos,
        BigDecimal pozoNeto) {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    static CuentasTorneo de(Torneo torneo, Integer participantes, BigDecimal gastos) {
        BigDecimal recaudacion = torneo.getValorInscripcion() == null || participantes == null ? null
                : torneo.getValorInscripcion().multiply(BigDecimal.valueOf(participantes));
        BigDecimal pctComision = EconomiaTorneo.comision(torneo.getPctPozoCompetidores(), torneo.getPctPremioRifa());
        BigDecimal pozoCompetidores = porcentaje(recaudacion, torneo.getPctPozoCompetidores());
        return new CuentasTorneo(recaudacion, porcentaje(recaudacion, pctComision),
                porcentaje(recaudacion, torneo.getPctPremioRifa()), pozoCompetidores, gastos,
                pozoCompetidores == null ? null : pozoCompetidores.subtract(gastos));
    }

    /** Lo que le toca a un puesto del pozo neto. */
    BigDecimal delPuesto(BigDecimal pct) {
        return porcentaje(pozoNeto, pct);
    }

    private static BigDecimal porcentaje(BigDecimal monto, BigDecimal pct) {
        return monto == null || pct == null ? null : monto.multiply(pct).divide(CIEN, 2, RoundingMode.HALF_UP);
    }
}
