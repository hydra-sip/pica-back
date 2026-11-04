package com.hydra.pica.plataforma_pica.torneo.event;

import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;

/**
 * Publicado cuando cambia el estado general de un torneo (HU-305).
 * Lo escucha el servicio de tiempo real para notificar en /topic/torneos/{id}.
 */
public record TorneoEstadoCambiado(Long torneoId, EstadoTorneo estadoAnterior, EstadoTorneo estadoNuevo) {
}
