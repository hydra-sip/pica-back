package com.hydra.pica.plataforma_pica.torneo.domain;

/**
 * Dónde está el pago de un gasto previsto (HU-604).
 * <ul>
 * <li>PENDIENTE: nadie lo pagó todavía.</li>
 * <li>EN_CUSTODIA: un honorario depositado, retenido en la custodia del torneo hasta la liquidación.</li>
 * <li>ACREDITADO: la liquidación se lo acreditó al árbitro.</li>
 * <li>REINTEGRADO: el torneo se canceló y el depósito volvió al organizador.</li>
 * <li>PAGADO: un gasto común que el organizador pagó por fuera, con su comprobante.</li>
 * </ul>
 */
public enum EstadoPagoGasto {
    PENDIENTE,
    EN_CUSTODIA,
    ACREDITADO,
    REINTEGRADO,
    PAGADO
}
