package com.hydra.pica.plataforma_pica.reputacion.dto;

import com.hydra.pica.plataforma_pica.user.dto.UsuarioPublico;

/**
 * Un jugador al que se puede sancionar en el torneo (HU-803): el de una inscripción individual confirmada o uno de la
 * nómina de un equipo confirmado.
 *
 * @param equipo el nombre del equipo; null si la inscripción es individual
 */
public record JugadorSancionable(Long inscripcionId, String equipo, UsuarioPublico jugador) {
}
