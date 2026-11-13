package com.hydra.pica.plataforma_pica.billetera.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;

/**
 * Cómo viene la plata de un torneo para su organizador (HU-608), en vivo y con la cuenta de la liquidación (HU-606):
 * de lo recaudado se le reintegran sus gastos, hasta lo recaudado, y el resto más lo que aporta la rifa va a premios.
 * Liquidado, los números ya no cambian.
 *
 * @param recaudado             lo que pagaron los inscriptos y está en custodia, sin gastos administrativos
 * @param gastosAdministrativos lo que pagaron aparte los inscriptos: va a la plataforma
 * @param aporteRifa            lo que su rifa destina al pozo, por lo vendido hasta ahora
 * @param gastos                los gastos del torneo que se reintegran: los comunes y los honorarios depositados
 * @param reintegroOrganizador  los gastos, hasta lo recaudado: lo que cobra el organizador
 * @param comprometidoEnPremios lo recaudado menos el reintegro, más el aporte de la rifa
 * @param pagos                 cuántas inscripciones pagaron
 */
public record PanelFinanciero(
        Long torneoId,
        String torneoNombre,
        EstadoTorneo estado,
        boolean liquidado,
        Instant liquidadoEn,
        String moneda,
        BigDecimal recaudado,
        BigDecimal gastosAdministrativos,
        BigDecimal aporteRifa,
        BigDecimal gastos,
        BigDecimal reintegroOrganizador,
        BigDecimal comprometidoEnPremios,
        long pagos) {
}
