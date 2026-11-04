package com.hydra.pica.plataforma_pica.rifa.event;

/**
 * La rifa se sorteó y sus premios tienen ganador (HU-504). Se publica dentro de la transacción del sorteo; el tiempo
 * real lo manda después del commit. {@code torneoId} es null si la rifa no tiene torneo.
 */
public record RifaSorteada(Long rifaId, Long torneoId) {
}
