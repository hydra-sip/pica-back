package com.hydra.pica.plataforma_pica.reputacion.dto;

import com.hydra.pica.plataforma_pica.user.dto.UsuarioPublico;

/**
 * Un jugador del torneo terminado que el organizador puede calificar (HU-805), con la calificación si ya la tiene.
 *
 * @param equipo el nombre del equipo; null si la inscripción es individual
 * @param calificacion null mientras no lo califique
 */
public record JugadorCalificable(Long inscripcionId, String equipo, UsuarioPublico jugador,
        CalificacionDetalle calificacion) {
}
