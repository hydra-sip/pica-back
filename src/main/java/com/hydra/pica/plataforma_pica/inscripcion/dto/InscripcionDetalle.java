package com.hydra.pica.plataforma_pica.inscripcion.dto;

import java.time.Instant;
import java.util.List;

import com.hydra.pica.plataforma_pica.equipo.domain.Equipo;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.RolEnInscripcion;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioPublico;

/**
 * Una inscripción. La individual lleva el usuario; la de equipo lleva el equipo y la nómina, y su
 * {@code usuarioId} es nulo (el capitán es el del equipo). {@code rol} es el de quien la pide.
 */
public record InscripcionDetalle(
        Long id,
        Long torneoId,
        TorneoDeInscripcion torneo,
        Long usuarioId,
        EstadoInscripcion estado,
        Instant reglamentoAceptadoEn,
        Instant creadoEn,
        Instant modificadoEn,
        EquipoInscripto equipo,
        List<UsuarioPublico> nomina,
        RolEnInscripcion rol) {

    /** {@code nomina}: los jugadores de la inscripción, solo en las de equipo (en la individual, nulo). */
    public static InscripcionDetalle de(Inscripcion inscripcion, List<UsuarioPublico> nomina, Long usuarioId) {
        Equipo equipo = inscripcion.getEquipo();
        return new InscripcionDetalle(inscripcion.getId(), inscripcion.getTorneo().getId(),
                TorneoDeInscripcion.de(inscripcion.getTorneo()),
                inscripcion.getUsuario() == null ? null : inscripcion.getUsuario().getId(), inscripcion.getEstado(),
                inscripcion.getReglamentoAceptadoEn(), inscripcion.getCreadoEn(), inscripcion.getModificadoEn(),
                EquipoInscripto.de(equipo), nomina, RolEnInscripcion.de(inscripcion, usuarioId));
    }

    /** {@code capitanId}: para marcar al capitán en la nómina, mire quien mire (HU-209). */
    public record EquipoInscripto(Long id, String nombre, Long capitanId) {

        /** Nulo en las individuales, que no tienen equipo. */
        public static EquipoInscripto de(Equipo equipo) {
            return equipo == null ? null
                    : new EquipoInscripto(equipo.getId(), equipo.getNombre(), equipo.getCapitan().getId());
        }
    }
}
