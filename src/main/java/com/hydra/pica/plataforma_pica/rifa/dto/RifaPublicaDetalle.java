package com.hydra.pica.plataforma_pica.rifa.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.PremioRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;

/**
 * Lo que ve de una rifa quien la va a comprar (HU-403), sin login. Sin datos del organizador ni de los
 * compradores. {@code torneo} es null si la rifa no tiene torneo; su {@code slug} lleva a la ficha pública.
 */
public record RifaPublicaDetalle(
        Long id,
        String titulo,
        String descripcion,
        EstadoRifa estado,
        TorneoDeRifa torneo,
        int pctDestinadoTorneo,
        int numeroDesde,
        int numeroHasta,
        BigDecimal precioNumero,
        String moneda,
        Instant ventaDesde,
        Instant ventaHasta,
        Instant fechaSorteo,
        List<PremioRifaDetalle> premios) {

    public record TorneoDeRifa(Long id, String nombre, String slug) {
    }

    public static RifaPublicaDetalle de(Rifa rifa, List<PremioRifa> premios) {
        Torneo torneo = rifa.getTorneo();
        return new RifaPublicaDetalle(rifa.getId(), rifa.getTitulo(), rifa.getDescripcion(), rifa.getEstado(),
                torneo == null ? null : new TorneoDeRifa(torneo.getId(), torneo.getNombre(), torneo.getSlug()),
                rifa.getPctDestinadoTorneo(), rifa.getNumeroDesde(), rifa.getNumeroHasta(), rifa.getPrecioNumero(),
                rifa.getMoneda(), rifa.getVentaDesde(), rifa.getVentaHasta(), rifa.getSorteadaEn(),
                premios.stream().map(PremioRifaDetalle::de).toList());
    }
}
