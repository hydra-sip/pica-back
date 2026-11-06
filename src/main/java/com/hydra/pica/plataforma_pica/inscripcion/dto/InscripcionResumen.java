package com.hydra.pica.plataforma_pica.inscripcion.dto;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.equipo.domain.Equipo;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.RolEnInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionDetalle.EquipoInscripto;

/**
 * Una inscripción sin la nómina. La individual lleva el usuario; la de equipo lleva el equipo y su
 * {@code usuarioId} es nulo, como en {@link InscripcionDetalle}. {@code rol} es el del usuario que la consulta.
 */
public record InscripcionResumen(
        Long id,
        Long torneoId,
        TorneoDeInscripcion torneo,
        Long usuarioId,
        EstadoInscripcion estado,
        Instant creadoEn,
        Instant modificadoEn,
        EquipoInscripto equipo,
        RolEnInscripcion rol) {

    /**
     * Una fila de Mis inscripciones de {@code usuarioId}: si no es la individual ni la de un equipo que capitanea,
     * vino por la nómina.
     */
    public static InscripcionResumen de(Inscripcion inscripcion, Long usuarioId) {
        Equipo equipo = inscripcion.getEquipo();
        return new InscripcionResumen(inscripcion.getId(), inscripcion.getTorneo().getId(),
                TorneoDeInscripcion.de(inscripcion.getTorneo()),
                inscripcion.getUsuario() == null ? null : inscripcion.getUsuario().getId(), inscripcion.getEstado(),
                inscripcion.getCreadoEn(), inscripcion.getModificadoEn(),
                EquipoInscripto.de(equipo), rolDe(inscripcion, usuarioId));
    }

    private static RolEnInscripcion rolDe(Inscripcion inscripcion, Long usuarioId) {
        if (!inscripcion.esDeEquipo()) {
            return RolEnInscripcion.INDIVIDUAL;
        }
        return inscripcion.getEquipo().getCapitan().getId().equals(usuarioId)
                ? RolEnInscripcion.CAPITAN
                : RolEnInscripcion.JUGADOR;
    }
}
