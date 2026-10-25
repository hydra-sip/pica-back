package com.hydra.pica.plataforma_pica.torneo.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Las cuentas del paso 3 (PICA-203), estimadas con el cupo completo y sin la rifa: recaudación = inscripción × cupo,
 * de ahí la comisión, el premio de la rifa y el pozo de competidores por sus %, y el pozo neto es el de competidores
 * menos los gastos. Lo que no se puede calcular porque falta un dato va en null.
 *
 * @param pozoNeto puede quedar negativo si los gastos superan el pozo; publicar lo rechaza
 * @param premios lo que le toca a cada puesto del pozo neto
 */
public record ResumenEconomico(
        Integer participantes,
        BigDecimal valorInscripcion,
        String moneda,
        BigDecimal recaudacion,
        BigDecimal comision,
        BigDecimal premioRifa,
        BigDecimal pozoCompetidores,
        BigDecimal gastos,
        BigDecimal pozoNeto,
        List<Puesto> premios) {

    /** {@code monto} es null mientras no se pueda calcular el pozo neto. */
    public record Puesto(int puesto, BigDecimal pct, BigDecimal monto) {
    }
}
