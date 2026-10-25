package com.hydra.pica.plataforma_pica.inscripcion.dto;

import java.time.Instant;
import java.util.List;

import com.hydra.pica.plataforma_pica.equipo.domain.Equipo;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioPublico;

/**
 * Una inscripción. La individual lleva el usuario; la de equipo lleva el equipo y la nómina, y su
 * {@code usuarioId} es nulo (el capitán es el del equipo).
 */
public record InscripcionDetalle(
        Long id,
        Long torneoId,
        Long usuarioId,
        EstadoInscripcion estado,
        Instant reglamentoAceptadoEn,
        Instant creadoEn,
        Instant modificadoEn,
        EquipoInscripto equipo,
        List<UsuarioPublico> nomina) {

    public static InscripcionDetalle de(Inscripcion inscripcion) {
        return de(inscripcion, null);
    }

    /** {@code nomina}: los jugadores de la inscripción, solo en las de equipo. */
    public static InscripcionDetalle de(Inscripcion inscripcion, List<UsuarioPublico> nomina) {
        Equipo equipo = inscripcion.getEquipo();
        return new InscripcionDetalle(inscripcion.getId(), inscripcion.getTorneo().getId(),
                inscripcion.getUsuario() == null ? null : inscripcion.getUsuario().getId(), inscripcion.getEstado(),
                inscripcion.getReglamentoAceptadoEn(), inscripcion.getCreadoEn(), inscripcion.getModificadoEn(),
                equipo == null ? null : new EquipoInscripto(equipo.getId(), equipo.getNombre()), nomina);
    }

    public record EquipoInscripto(Long id, String nombre) {
    }
}
