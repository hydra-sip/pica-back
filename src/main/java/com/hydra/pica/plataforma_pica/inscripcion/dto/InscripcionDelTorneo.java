package com.hydra.pica.plataforma_pica.inscripcion.dto;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionDetalle.EquipoInscripto;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioPublico;

/**
 * Una inscripción como la ve el organizador en la lista de su torneo (HU-206, PICA-214). La individual lleva el
 * usuario; la de equipo, el equipo, el capitán y cuántos hay en la nómina. La nómina completa está en
 * {@link InscripcionDetalle}.
 *
 * @param jugadores en la de equipo, los de la nómina con el capitán incluido; null en la individual
 */
public record InscripcionDelTorneo(
        Long id,
        EstadoInscripcion estado,
        Instant creadoEn,
        UsuarioPublico usuario,
        EquipoInscripto equipo,
        UsuarioPublico capitan,
        Integer jugadores) {

    public static InscripcionDelTorneo de(Inscripcion inscripcion, int jugadores) {
        if (!inscripcion.esDeEquipo()) {
            return new InscripcionDelTorneo(inscripcion.getId(), inscripcion.getEstado(), inscripcion.getCreadoEn(),
                    UsuarioPublico.desde(inscripcion.getUsuario()), null, null, null);
        }
        return new InscripcionDelTorneo(inscripcion.getId(), inscripcion.getEstado(), inscripcion.getCreadoEn(), null,
                EquipoInscripto.de(inscripcion.getEquipo()), UsuarioPublico.desde(inscripcion.titular()), jugadores);
    }
}
