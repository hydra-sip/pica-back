package com.hydra.pica.plataforma_pica.rifa.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * El comprobante de una compra de números (HU-403). {@code monto} va al pozo de la rifa; {@code gastosAdministrativos}
 * se cobra aparte y {@code total} es lo que se cobró. {@code pozoRifa} y {@code pozoTorneo} son los de ahora; el del
 * torneo es null si la rifa no tiene torneo.
 */
public record VentaRifaResultado(
        Long ticketId,
        String ticketCodigo,
        List<Integer> numeros,
        BigDecimal monto,
        BigDecimal gastosAdministrativos,
        BigDecimal total,
        Instant compradoEn,
        BigDecimal pozoRifa,
        BigDecimal pozoTorneo) {
}
