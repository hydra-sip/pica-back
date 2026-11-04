package com.hydra.pica.plataforma_pica.partido.dto;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.partido.domain.Decision;

/**
 * Una objeción en el listado del torneo (HU-303), sin evidencias: están en el comparativo del partido.
 *
 * @param local    el del equipo o, si se inscribe un jugador, su nombre y apellido
 * @param cargada  la que cargó el capitán, aunque un laudo CORREGIDO haya cambiado el resultado del partido
 * @param decision null mientras está pendiente
 */
public record ObjecionResumen(
        Long partidoId,
        int ronda,
        String nombreRonda,
        String local,
        String visitante,
        VersionResultado cargada,
        VersionResultado objetada,
        String motivo,
        Instant creadaEn,
        Decision decision,
        Instant resueltaEn) {
}
