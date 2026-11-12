package com.hydra.pica.plataforma_pica.rifa.dto;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.rifa.domain.EstadoReclamo;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.ReclamoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;

/**
 * Vista administrativa de un reclamo de rifa con los datos de la rifa y del reclamante (HU-706, PICA-339).
 */
public record ReclamoAdminDetalle(
        Long id,
        String motivo,
        EstadoReclamo estado,
        String resolucion,
        Instant resueltoEn,
        String resueltoPor,
        Instant creadoEn,
        RifaReclamoResumen rifa,
        ReclamanteResumen reclamante
) {

    public record RifaReclamoResumen(
            Long id,
            String titulo,
            EstadoRifa estado,
            String torneoNombre,
            Instant fechaSorteo,
            Instant semillaObtenidaEn
    ) {
    }

    public record ReclamanteResumen(
            Long id,
            String username,
            String email
    ) {
    }

    public static ReclamoAdminDetalle de(ReclamoRifa reclamo) {
        Rifa rifa = reclamo.getRifa();
        Usuario reclamante = reclamo.getUsuario();
        return new ReclamoAdminDetalle(
                reclamo.getId(),
                reclamo.getMotivo(),
                reclamo.getEstado(),
                reclamo.getResolucion(),
                reclamo.getResueltoEn(),
                reclamo.getResueltoPor(),
                reclamo.getCreadoEn(),
                new RifaReclamoResumen(
                        rifa.getId(),
                        rifa.getTitulo(),
                        rifa.getEstado(),
                        rifa.getTorneo() != null ? rifa.getTorneo().getNombre() : null,
                        rifa.getSorteadaEn(),
                        rifa.getSemillaObtenidaEn()
                ),
                new ReclamanteResumen(
                        reclamante.getId(),
                        reclamante.getUsername(),
                        reclamante.getEmail()
                )
        );
    }
}

