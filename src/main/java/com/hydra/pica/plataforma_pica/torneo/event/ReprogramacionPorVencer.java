package com.hydra.pica.plataforma_pica.torneo.event;

/**
 * Publicado cuando falta un día o menos para que venza el plazo para rechazar la nueva fecha (CR-01-4): el recordatorio
 * a los que siguen inscriptos. Sale una sola vez por reprogramación.
 */
public record ReprogramacionPorVencer(Long torneoId, Long reprogramacionId) {
}
