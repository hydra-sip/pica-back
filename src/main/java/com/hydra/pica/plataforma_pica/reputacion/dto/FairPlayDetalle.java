package com.hydra.pica.plataforma_pica.reputacion.dto;

import java.time.Instant;
import java.util.List;
import com.hydra.pica.plataforma_pica.reputacion.domain.EventoFairPlay;
import com.hydra.pica.plataforma_pica.reputacion.domain.Gravedad;
import com.hydra.pica.plataforma_pica.reputacion.domain.TipoEventoFairPlay;

/**
 * El índice de fair play de un usuario (HU-802): el valor, cuándo se calculó (null si nunca se movió: tiene el
 * inicial) y el historial, el último primero.
 */
public record FairPlayDetalle(Long usuarioId, int valor, Instant actualizadoEn, List<Movimiento> historial) {

    /** Qué movió el índice: el torneo, y el partido (check-in o ausencia) o la sanción. */
    public record Movimiento(
            TipoEventoFairPlay tipo,
            int delta,
            int valorResultante,
            Long torneoId,
            String torneo,
            Long partidoId,
            Long sancionId,
            Gravedad gravedad,
            Instant creadoEn) {

        public static Movimiento de(EventoFairPlay evento) {
            return new Movimiento(evento.getTipo(), evento.getDelta(), evento.getValorResultante(),
                    evento.getTorneo().getId(), evento.getTorneo().getNombre(),
                    evento.getPartido() == null ? null : evento.getPartido().getId(),
                    evento.getSancion() == null ? null : evento.getSancion().getId(),
                    evento.getSancion() == null ? null : evento.getSancion().getGravedad(), evento.getCreadoEn());
        }
    }
}
