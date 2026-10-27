package com.hydra.pica.plataforma_pica.partido.dto;

import java.util.List;

import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;

/**
 * Las dos versiones de un partido objetado lado a lado, cada una con su evidencia, para laudar con criterio (HU-303).
 *
 * @param laudo     null mientras está pendiente
 * @param historial los registros de auditoría del partido, del más viejo al más nuevo
 */
public record ObjecionComparada(
        Long partidoId,
        Long torneoId,
        EstadoPartido estado,
        VersionResultado cargada,
        VersionResultado objetada,
        String motivo,
        LaudoDetalle laudo,
        List<EventoPartido> historial) {
}
