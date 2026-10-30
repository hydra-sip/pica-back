package com.hydra.pica.plataforma_pica.rifa.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.PremioRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.RepartoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;

/**
 * Una rifa con sus premios, en orden de puesto. {@code fechaSorteo} es la programada (columna sorteada_en).
 * Sin torneo, {@code torneoId} y {@code torneoNombre} son null y {@code pctDestinadoTorneo} es 0.
 * {@code numerosVendidos}: mientras sea 0, la rifa se puede volver a borrador o eliminar (PICA-287).
 * {@code reparto}: lo recaudado con los vendidos, lo que va al pozo del torneo y lo que va a los premios (HU-405).
 */
public record RifaDetalle(
        Long id,
        String titulo,
        String descripcion,
        Long torneoId,
        String torneoNombre,
        int pctDestinadoTorneo,
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
        long numerosVendidos,
        RepartoRifa reparto,
        Instant creadoEn,
        Instant modificadoEn) {

    public static RifaDetalle de(Rifa rifa, List<PremioRifa> premios, long numerosVendidos) {
        Torneo torneo = rifa.getTorneo();
        return new RifaDetalle(rifa.getId(), rifa.getTitulo(), rifa.getDescripcion(),
                torneo == null ? null : torneo.getId(), torneo == null ? null : torneo.getNombre(),
                rifa.getPctDestinadoTorneo(), rifa.getEstado(),
                rifa.getNumeroDesde(), rifa.getNumeroHasta(), rifa.getPrecioNumero(), rifa.getMoneda(),
                rifa.getVentaDesde(), rifa.getVentaHasta(), rifa.getSorteadaEn(), rifa.getTerminosAceptadosEn(),
                rifa.getLoteriaDeclaradaEn(),
                premios.stream().map(PremioRifaDetalle::de).toList(), numerosVendidos,
                RepartoRifa.de(rifa, numerosVendidos), rifa.getCreadoEn(),
                rifa.getModificadoEn());
    }
}
