package com.hydra.pica.plataforma_pica.notificacion.dto;

import java.time.Instant;
import java.util.Map;

import com.hydra.pica.plataforma_pica.notificacion.domain.Notificacion;
import com.hydra.pica.plataforma_pica.notificacion.domain.TipoNotificacion;

/** Schema Notificacion del contrato: lo que devuelve GET /me/notificaciones y lo que llega por WebSocket. */
public record NotificacionDetalle(
        Long id,
        TipoNotificacion tipo,
        String titulo,
        String cuerpo,
        String enlace,
        Map<String, Object> datos,
        Instant leidaEn,
        Instant creadaEn) {

    public static NotificacionDetalle desde(Notificacion notificacion) {
        return new NotificacionDetalle(
                notificacion.getId(),
                notificacion.getTipo(),
                notificacion.getTitulo(),
                notificacion.getCuerpo(),
                notificacion.getEnlace(),
                notificacion.getDatos() == null ? Map.of() : notificacion.getDatos(),
                notificacion.getLeidaEn(),
                notificacion.getCreadaEn());
    }
}
