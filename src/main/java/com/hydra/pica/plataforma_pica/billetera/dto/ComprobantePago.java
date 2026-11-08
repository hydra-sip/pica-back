package com.hydra.pica.plataforma_pica.billetera.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.hydra.pica.plataforma_pica.billetera.domain.EstadoCustodia;

/**
 * El comprobante del pago de una inscripción (HU-603). El de una compra de números es su ticket.
 *
 * @param numero   I{torneo}-{movimiento del pago}, como I12-345
 * @param custodia en qué quedó la plata: RETENIDA hasta que termine el torneo
 */
public record ComprobantePago(
        String numero,
        Instant pagadoEn,
        String pagadorNombre,
        String pagadorUsername,
        String concepto,
        Long torneoId,
        String torneoNombre,
        String equipoNombre,
        BigDecimal monto,
        BigDecimal gastosAdministrativos,
        BigDecimal total,
        String moneda,
        EstadoCustodia custodia) {
}
