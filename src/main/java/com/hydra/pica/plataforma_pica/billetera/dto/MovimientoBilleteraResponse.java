package com.hydra.pica.plataforma_pica.billetera.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.hydra.pica.plataforma_pica.billetera.domain.EstadoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;

/**
 * Detalle de un movimiento en la billetera virtual (HU-602, T602-1).
 */
public record MovimientoBilleteraResponse(
        Long id,
        TipoMovimiento tipo,
        BigDecimal monto,
        String moneda,
        BigDecimal saldoResultante,
        EstadoMovimiento estado,
        String referenciaExterna,
        String descripcion,
        TorneoMovimiento torneo,
        RifaMovimiento rifa,
        Instant creadoEn) {

    public static MovimientoBilleteraResponse de(Movimiento movimiento, Rifa rifa) {
        Torneo torneo = movimiento.getTorneo();
        if (torneo == null && movimiento.getInscripcion() != null) {
            torneo = movimiento.getInscripcion().getTorneo();
        }
        if (torneo == null && rifa != null && rifa.getTorneo() != null) {
            torneo = rifa.getTorneo();
        }
        return new MovimientoBilleteraResponse(
                movimiento.getId(),
                movimiento.getTipo(),
                movimiento.getMonto(),
                movimiento.getMoneda(),
                movimiento.getSaldoResultante(),
                movimiento.getEstado(),
                movimiento.getReferenciaExterna(),
                movimiento.getDescripcion(),
                TorneoMovimiento.de(torneo),
                RifaMovimiento.de(rifa),
                movimiento.getCreadoEn());
    }
}

