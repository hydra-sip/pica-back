package com.hydra.pica.plataforma_pica.inscripcion.dto;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;

/** Una inscripción individual; la de equipo suma el equipo y la nómina (ticket 05). */
public record InscripcionDetalle(
        Long id,
        Long torneoId,
        Long usuarioId,
        EstadoInscripcion estado,
        Instant reglamentoAceptadoEn,
        Instant creadoEn,
        Instant modificadoEn) {

    public static InscripcionDetalle de(Inscripcion inscripcion) {
        return new InscripcionDetalle(inscripcion.getId(), inscripcion.getTorneo().getId(),
                inscripcion.getUsuario().getId(), inscripcion.getEstado(), inscripcion.getReglamentoAceptadoEn(),
                inscripcion.getCreadoEn(), inscripcion.getModificadoEn());
    }
}
