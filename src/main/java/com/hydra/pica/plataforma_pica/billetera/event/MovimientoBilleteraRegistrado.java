package com.hydra.pica.plataforma_pica.billetera.event;

import java.math.BigDecimal;

import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;

/**
 * Publicado cuando se acredita una carga o se solicita un retiro en la billetera del usuario (HU-705).
 */
public record MovimientoBilleteraRegistrado(
        Long movimientoId,
        Long usuarioId,
        TipoMovimiento tipo,
        BigDecimal monto
) {
}
