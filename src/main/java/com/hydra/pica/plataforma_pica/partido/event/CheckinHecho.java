package com.hydra.pica.plataforma_pica.partido.event;

/** Un lado hizo check-in en su ventana (HU-308): suma al índice de fair play de quien responde por él (HU-802). */
public record CheckinHecho(Long torneoId, Long partidoId, Long inscripcionId) {
}
