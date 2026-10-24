package com.hydra.pica.plataforma_pica.rifa.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;

/** Fila de "Mis rifas". */
public record RifaResumen(
        Long id,
        String titulo,
        EstadoRifa estado,
        int numeroDesde,
        int numeroHasta,
        BigDecimal precioNumero,
        String moneda,
        Instant ventaDesde,
        Instant ventaHasta,
        Instant creadoEn) {

    public static RifaResumen de(Rifa rifa) {
        return new RifaResumen(rifa.getId(), rifa.getTitulo(), rifa.getEstado(), rifa.getNumeroDesde(),
                rifa.getNumeroHasta(), rifa.getPrecioNumero(), rifa.getMoneda(), rifa.getVentaDesde(),
                rifa.getVentaHasta(), rifa.getCreadoEn());
    }
}
