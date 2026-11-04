package com.hydra.pica.plataforma_pica.partido.dto;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;

/**
 * Un partido del bracket. Los lados están en null hasta que se sabe quién juega.
 *
 * @param bye el local pasa a la ronda siguiente sin jugar: no tiene rival
 */
public record PartidoBracket(
        Long id,
        int ronda,
        int posicion,
        EstadoPartido estado,
        boolean bye,
        LadoBracket local,
        LadoBracket visitante,
        String cancha,
        Instant fechaProgramada,
        String arbitro) {
}
