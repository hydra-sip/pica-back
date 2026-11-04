package com.hydra.pica.plataforma_pica.partido.dto;

import java.util.List;

/**
 * Respuesta del ranking de pronósticos (HU-307, T307-1).
 *
 * @param torneoId   id del torneo si se filtró por uno, o null si es ranking global
 * @param posiciones tabla de posiciones ordenada por puntaje y criterio de desempate
 * @param miPosicion estado o posición del usuario autenticado que consulta (null si es anónimo)
 */
public record RankingResponse(
        Long torneoId,
        List<PosicionRanking> posiciones,
        MiPosicionRanking miPosicion) {
}
