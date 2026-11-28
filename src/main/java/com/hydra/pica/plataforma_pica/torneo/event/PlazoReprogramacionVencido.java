package com.hydra.pica.plataforma_pica.torneo.event;

/**
 * Publicado cuando vence el plazo para rechazar la nueva fecha y la inscripción se abrió o se volvió a cerrar
 * (CR-01-2). Lo escucha el aviso al organizador (CR-01-4) después del commit.
 */
public record PlazoReprogramacionVencido(Long torneoId, Long reprogramacionId) {
}
