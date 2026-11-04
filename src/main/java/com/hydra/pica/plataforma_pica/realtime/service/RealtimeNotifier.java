package com.hydra.pica.plataforma_pica.realtime.service;

import com.hydra.pica.plataforma_pica.realtime.dto.EventoRifaMessage;
import com.hydra.pica.plataforma_pica.realtime.dto.EventoTorneoMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Publicador WebSocket para los topics /topic/torneos/{id} y /topic/rifas/{id} (HU-305, T305-1).
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
}
