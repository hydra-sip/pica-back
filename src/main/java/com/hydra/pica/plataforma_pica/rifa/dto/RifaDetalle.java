package com.hydra.pica.plataforma_pica.rifa.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.PremioRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;

/** Una rifa con sus premios, en orden de puesto. {@code fechaSorteo} es la programada (columna sorteada_en). */
public record RifaDetalle(
        Long id,
        String titulo,
        String descripcion,
        EstadoRifa estado,
        int numeroDesde,
        int numeroHasta,
        BigDecimal precioNumero,
        String moneda,
        Instant ventaDesde,
        Instant ventaHasta,
        Instant fechaSorteo,
        Instant terminosAceptadosEn,
        Instant loteriaDeclaradaEn,
        List<PremioRifaDetalle> premios,
        Instant creadoEn,
        Instant modificadoEn) {

    public static RifaDetalle de(Rifa rifa, List<PremioRifa> premios) {
        return new RifaDetalle(rifa.getId(), rifa.getTitulo(), rifa.getDescripcion(), rifa.getEstado(),
                rifa.getNumeroDesde(), rifa.getNumeroHasta(), rifa.getPrecioNumero(), rifa.getMoneda(),
                rifa.getVentaDesde(), rifa.getVentaHasta(), rifa.getSorteadaEn(), rifa.getTerminosAceptadosEn(),
                rifa.getLoteriaDeclaradaEn(),
                premios.stream().map(PremioRifaDetalle::de).toList(), rifa.getCreadoEn(), rifa.getModificadoEn());
    }
}
