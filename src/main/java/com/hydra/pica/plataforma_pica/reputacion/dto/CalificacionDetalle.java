package com.hydra.pica.plataforma_pica.reputacion.dto;

import java.time.Instant;
import com.hydra.pica.plataforma_pica.reputacion.domain.Calificacion;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioPublico;

/** Una calificación como la devuelve la API: el torneo, el jugador, quién la puso, cuándo y qué puso. */
public record CalificacionDetalle(
        Long id,
        Long torneoId,
        String torneo,
        UsuarioPublico jugador,
        UsuarioPublico organizador,
        int puntaje,
        String comentario,
        boolean noConfiable,
        Instant creadaEn) {

    public static CalificacionDetalle desde(Calificacion calificacion) {
        return new CalificacionDetalle(calificacion.getId(), calificacion.getTorneo().getId(),
                calificacion.getTorneo().getNombre(), UsuarioPublico.desde(calificacion.getUsuario()),
                UsuarioPublico.desde(calificacion.getOrganizador()), calificacion.getPuntaje(),
                calificacion.getComentario(), calificacion.isNoConfiable(), calificacion.getCreadaEn());
    }
}
