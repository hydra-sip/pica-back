package com.hydra.pica.plataforma_pica.rifa.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;

/** Fila del listado público de rifas en venta (HU-403). {@code torneoNombre} es null si la rifa no tiene torneo. */
public record RifaPublicaResumen(
        Long id,
        String titulo,
        String torneoNombre,
        int numeroDesde,
        int numeroHasta,
        BigDecimal precioNumero,
        String moneda,
        Instant ventaDesde,
        Instant ventaHasta,
        Instant fechaSorteo) {

    public static RifaPublicaResumen de(Rifa rifa) {
        return new RifaPublicaResumen(rifa.getId(), rifa.getTitulo(),
                rifa.getTorneo() == null ? null : rifa.getTorneo().getNombre(), rifa.getNumeroDesde(),
                rifa.getNumeroHasta(), rifa.getPrecioNumero(), rifa.getMoneda(), rifa.getVentaDesde(),
                rifa.getVentaHasta(), rifa.getSorteadaEn());
    }
}
