package com.hydra.pica.plataforma_pica.rifa.event;

/**
 * La venta de una rifa se cerró y su lista quedó sellada (HU-407). Lo escucha el tiempo real para avisar en
 * /topic/rifas/{id} que la rifa pasó a CERRADA.
 *
 * @param rifaId   la rifa cerrada
 * @param torneoId su torneo; null si no tiene
 */
public record VentaRifaCerrada(Long rifaId, Long torneoId) {
}
