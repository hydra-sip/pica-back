package com.hydra.pica.plataforma_pica.rifa.event;

/**
 * Una rifa se canceló sola porque terminó la venta sin ningún número vendido (PICA-445). Lo escucha el tiempo real
 * para avisar en /topic/rifas/{id} que la rifa pasó a CANCELADA.
 *
 * @param rifaId   la rifa cancelada
 * @param torneoId su torneo; null si no tiene
 */
public record RifaCancelada(Long rifaId, Long torneoId) {
}
