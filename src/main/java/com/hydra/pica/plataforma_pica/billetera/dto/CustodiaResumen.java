package com.hydra.pica.plataforma_pica.billetera.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.hydra.pica.plataforma_pica.billetera.domain.EstadoCustodia;

/**
 * La custodia de un torneo o una rifa para su organizador (HU-603). {@code total} es todo lo retenido:
 * {@code recaudado} (inscripciones o números) más {@code gastosAdministrativos}.
 *
 * @param pagos           cuántos pagos entraron, sin contar los de gastos
 * @param eventoTerminado si el evento ya terminó; hasta entonces nadie mueve los fondos
 */
public record CustodiaResumen(
        EstadoCustodia estado,
        BigDecimal total,
        BigDecimal recaudado,
        BigDecimal gastosAdministrativos,
        long pagos,
        String moneda,
        boolean eventoTerminado,
        Instant liberadaEn) {
}
