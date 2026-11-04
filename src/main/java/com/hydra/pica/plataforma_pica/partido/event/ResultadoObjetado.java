package com.hydra.pica.plataforma_pica.partido.event;

/**
 * Lo publica {@code ResultadoService} cuando el rival objeta el resultado cargado (HU-302): el partido queda OBJETADO
 * hasta el laudo. Lo escuchan el aviso al organizador y a los árbitros, y el tiempo real (HU-305).
 */
public record ResultadoObjetado(Long torneoId, Long partidoId) {
}
