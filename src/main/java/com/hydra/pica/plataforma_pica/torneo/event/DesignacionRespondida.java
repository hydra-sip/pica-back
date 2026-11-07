package com.hydra.pica.plataforma_pica.torneo.event;

import com.hydra.pica.plataforma_pica.torneo.domain.EstadoDesignacion;

/** El árbitro aceptó o rechazó la designación (HU-807): se le avisa al organizador. */
public record DesignacionRespondida(Long torneoId, Long usuarioId, EstadoDesignacion estado) {
}
