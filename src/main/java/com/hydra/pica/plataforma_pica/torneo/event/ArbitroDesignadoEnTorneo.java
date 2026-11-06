package com.hydra.pica.plataforma_pica.torneo.event;

/** El organizador designó (o volvió a designar) a un árbitro (HU-807): se le avisa por mail. */
public record ArbitroDesignadoEnTorneo(Long torneoId, Long usuarioId) {
}
