package com.hydra.pica.plataforma_pica.torneo.event;

/**
 * La cancelación de un torneo se resolvió (HU-605): sin gastos, o soporte aprobó o rechazó los gastos. Ya se reintegró;
 * se avisa a cada uno que pagó y al organizador.
 */
public record CancelacionResuelta(Long torneoId) {
}
