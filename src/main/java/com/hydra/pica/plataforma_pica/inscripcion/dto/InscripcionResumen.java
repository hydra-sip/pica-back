package com.hydra.pica.plataforma_pica.inscripcion.dto;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.equipo.domain.Equipo;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionDetalle.EquipoInscripto;

/**
 * Una inscripción sin la nómina. La individual lleva el usuario; la de equipo lleva el equipo y su
 * {@code usuarioId} es nulo, como en {@link InscripcionDetalle}.
 */
public record InscripcionResumen(
        Long id,
        Long torneoId,
        TorneoDeInscripcion torneo,
        Long usuarioId,
        EstadoInscripcion estado,
        Instant creadoEn,
        Instant modificadoEn,
        EquipoInscripto equipo) {

    public static InscripcionResumen de(Inscripcion inscripcion) {
        Equipo equipo = inscripcion.getEquipo();
        return new InscripcionResumen(inscripcion.getId(), inscripcion.getTorneo().getId(),
                TorneoDeInscripcion.de(inscripcion.getTorneo()),
                inscripcion.getUsuario() == null ? null : inscripcion.getUsuario().getId(), inscripcion.getEstado(),
                inscripcion.getCreadoEn(), inscripcion.getModificadoEn(),
                EquipoInscripto.de(equipo));
    }
}
