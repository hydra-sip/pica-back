package com.hydra.pica.plataforma_pica.torneo.event;

/**
 * Publicado al reprogramar un torneo (CR-01, PICA-391). Lo escuchan los avisos (CR-01-4) después del commit: si el
 * correo o Discord fallan, la reprogramación queda hecha igual.
 */
public record TorneoReprogramado(Long torneoId, Long reprogramacionId) {
}
