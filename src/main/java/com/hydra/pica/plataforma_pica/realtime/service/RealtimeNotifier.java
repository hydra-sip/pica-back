package com.hydra.pica.plataforma_pica.realtime.service;

import com.hydra.pica.plataforma_pica.realtime.dto.EventoRifaMessage;
import com.hydra.pica.plataforma_pica.realtime.dto.EventoTorneoMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Publicador WebSocket para los topics /topic/torneos/{id} y /topic/rifas/{id} (HU-305, T305-1) y para la cola de
 * notificaciones de cada usuario, /user/queue/notificaciones (HU-808).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RealtimeNotifier {

    private final SimpMessagingTemplate messagingTemplate;

    public void publicarTorneo(Long torneoId, EventoTorneoMessage mensaje) {
        String destino = "/topic/torneos/" + torneoId;
        log.debug("Publicando evento en {}: {}", destino, mensaje);
        messagingTemplate.convertAndSend(destino, mensaje);
    }

    public void publicarRifa(Long rifaId, EventoRifaMessage mensaje) {
        String destino = "/topic/rifas/" + rifaId;
        log.debug("Publicando evento en {}: {}", destino, mensaje);
        messagingTemplate.convertAndSend(destino, mensaje);
    }

    /** Llega solo a las sesiones de ese usuario: el nombre del principal de la sesión STOMP es su id. */
    public void publicarUsuario(Long usuarioId, Object mensaje) {
        log.debug("Publicando notificación al usuario {}: {}", usuarioId, mensaje);
        messagingTemplate.convertAndSendToUser(usuarioId.toString(), "/queue/notificaciones", mensaje);
    }
}
