package com.hydra.pica.plataforma_pica.partido.event;

/**
 * Lo publica {@code AvanceLlave} cuando el resultado de un partido queda firme y el ganador ya pasó a la ronda
 * siguiente: porque el rival lo aceptó, porque venció el plazo para objetarlo, por el laudo del árbitro o por ausencia.
 * Lo escuchan los pronósticos (HU-306) y el tiempo real (HU-305).
 *
 * @param ganadorId       la inscripción que ganó
 * @param torneoFinalizado si era la final: el torneo terminó y las posiciones ya están guardadas
 */
public record ResultadoConfirmado(Long torneoId, Long partidoId, Long ganadorId, boolean torneoFinalizado) {
}
