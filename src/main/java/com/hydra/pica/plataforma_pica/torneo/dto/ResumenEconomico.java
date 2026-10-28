package com.hydra.pica.plataforma_pica.torneo.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Las cuentas del paso 3 (PICA-203), estimadas con el cupo completo y sin la rifa: recaudación = inscripción × cupo,
 * que va entera al pozo, y el pozo neto es la recaudación menos los gastos previstos. Los gastos administrativos son
 * por inscripción y se pagan aparte (PICA-283). Lo que no se puede calcular porque falta un dato va en null.
 *
 * @param pctGastosAdministrativos el sellado al publicar o, en BORRADOR, el vigente
 * @param gastosAdministrativos lo que paga aparte cada inscripción
 * @param totalAPagar valor de la inscripción más gastos administrativos
 * @param pozoNeto puede quedar negativo si los gastos superan el pozo; publicar lo rechaza
 * @param premios lo que le toca a cada puesto del pozo neto
 */
public record ResumenEconomico(
        Integer participantes,
        BigDecimal valorInscripcion,
        String moneda,
        BigDecimal pctGastosAdministrativos,
        BigDecimal gastosAdministrativos,
        BigDecimal totalAPagar,
        BigDecimal recaudacion,
        BigDecimal gastos,
        BigDecimal pozoNeto,
        List<Puesto> premios) {

    /** {@code monto} es null mientras no se pueda calcular el pozo neto. */
    public record Puesto(int puesto, BigDecimal pct, BigDecimal monto) {
    }
}
