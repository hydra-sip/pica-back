package com.hydra.pica.plataforma_pica.realtime.dto;

import java.math.BigDecimal;

import com.hydra.pica.plataforma_pica.partido.dto.BracketTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;

/**
 * Mensaje publicado en el topic /topic/torneos/{id} (HU-305, T305-1).
 *
 * @param tipo             tipo de evento: RESULTADO_CONFIRMADO, AVANCE_LLAVE, RESULTADO_OBJETADO, ESTADO_CAMBIADO, POZO_ACTUALIZADO, SINCRONIZACION
 * @param torneoId         id del torneo
 * @param estado           estado actual del torneo
 * @param partidoId        id del partido afectado (si aplica)
 * @param ganadorId        id de la inscripción ganadora (si aplica)
 * @param torneoFinalizado true si el torneo finalizó
 * @param bracket          bracket completo actualizado con los partidos y avance de la llave
 * @param pozo             pozo actualizado del torneo
 */
public record EventoTorneoMessage(
        String tipo,
        Long torneoId,
        EstadoTorneo estado,
        Long partidoId,
        Long ganadorId,
        Boolean torneoFinalizado,
        BracketTorneo bracket,
        BigDecimal pozo
) {
}
