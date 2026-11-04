package com.hydra.pica.plataforma_pica.rifa.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;

/** Fila de "Mis rifas". {@code torneoId} y {@code torneoNombre} son null si la rifa no tiene torneo. */
public record RifaResumen(
        Long id,
        String titulo,
        Long torneoId,
        String torneoNombre,
        EstadoRifa estado,
        int numeroDesde,
        int numeroHasta,
        BigDecimal precioNumero,
        String moneda,
        Instant ventaDesde,
        Instant ventaHasta,
        Instant fechaSorteo,
        Instant creadoEn) {

    public static RifaResumen de(Rifa rifa) {
        Torneo torneo = rifa.getTorneo();
        return new RifaResumen(rifa.getId(), rifa.getTitulo(), torneo == null ? null : torneo.getId(),
                torneo == null ? null : torneo.getNombre(), rifa.getEstado(),
                rifa.getNumeroDesde(), rifa.getNumeroHasta(), rifa.getPrecioNumero(), rifa.getMoneda(),
                rifa.getVentaDesde(), rifa.getVentaHasta(), rifa.getSorteadaEn(), rifa.getCreadoEn());
    }
}
