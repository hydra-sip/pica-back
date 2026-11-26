package com.hydra.pica.plataforma_pica.torneo.event;

/**
 * Publicado cuando un inscripto rechaza la nueva fecha del torneo y se le devuelve la inscripción (CR-01-3). Lo
 * escuchan los avisos (CR-01-4) después del commit; {@code bajoElMinimo} avisa al organizador que con las confirmadas
 * que quedan no llega al mínimo para jugar, para que decida (el torneo no se cancela solo).
 */
public record ReprogramacionRechazada(Long torneoId, Long reprogramacionId, Long inscripcionId, boolean bajoElMinimo) {
}
