package com.hydra.pica.plataforma_pica.realtime.controller;

import java.math.BigDecimal;
import java.util.List;

import com.hydra.pica.plataforma_pica.partido.dto.BracketTorneo;
import com.hydra.pica.plataforma_pica.partido.service.BracketService;
import com.hydra.pica.plataforma_pica.realtime.dto.EventoRifaMessage;
import com.hydra.pica.plataforma_pica.realtime.dto.EventoTorneoMessage;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoNumero;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Numero;
import com.hydra.pica.plataforma_pica.rifa.domain.RepartoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.repository.NumeroRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.torneo.service.PozoTorneoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;

/**
 * Controlador de WebSocket / STOMP para sincronización en tiempo real y reconexión (HU-305, T305-1).
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class RealtimeWebSocketController {

    private final TorneoRepository torneoRepository;
    private final BracketService bracketService;
    private final PozoTorneoService pozoTorneoService;
    private final RifaRepository rifaRepository;
    private final NumeroRepository numeroRepository;

    /**
     * Al suscribirse a /app/torneos/{id}, devuelve directamente al suscriptor el estado actual del torneo.
     */
    @SubscribeMapping("/torneos/{id}")
    public EventoTorneoMessage suscribirTorneo(@DestinationVariable Long id) {
        log.info("Cliente suscribiéndose a /app/torneos/{}", id);
        return obtenerEstadoTorneo(id);
    }

    /**
     * Mensaje de sincronización explícito (p. ej. al reconectar tras pérdida de conexión).
     */
    @MessageMapping("/torneos/{id}/sincronizar")
    @SendTo("/topic/torneos/{id}")
    public EventoTorneoMessage sincronizarTorneo(@DestinationVariable Long id) {
        log.info("Petición de sincronización para torneo {}", id);
        return obtenerEstadoTorneo(id);
    }

    /**
     * Al suscribirse a /app/rifas/{id}, devuelve directamente al suscriptor el estado actual de la rifa.
     */
    @SubscribeMapping("/rifas/{id}")
    public EventoRifaMessage suscribirRifa(@DestinationVariable Long id) {
        log.info("Cliente suscribiéndose a /app/rifas/{}", id);
        return obtenerEstadoRifa(id);
    }

    /**
     * Mensaje de sincronización explícito de rifa.
     */
    @MessageMapping("/rifas/{id}/sincronizar")
    @SendTo("/topic/rifas/{id}")
    public EventoRifaMessage sincronizarRifa(@DestinationVariable Long id) {
        log.info("Petición de sincronización para rifa {}", id);
        return obtenerEstadoRifa(id);
    }

    private EventoTorneoMessage obtenerEstadoTorneo(Long id) {
        Torneo torneo = torneoRepository.findById(id).orElse(null);
        EstadoTorneo estado = torneo != null ? torneo.getEstado() : null;
        BracketTorneo bracket = null;
        try {
            bracket = bracketService.ver(id);
        } catch (Exception e) {
            log.debug("Torneo {} sin bracket todavía", id);
        }
        BigDecimal pozo = pozoTorneoService.calcularPozo(id);

        return new EventoTorneoMessage(
                "SINCRONIZACION",
                id,
                estado,
                null,
                null,
                estado == EstadoTorneo.FINALIZADO,
                bracket,
                pozo
        );
    }

    private EventoRifaMessage obtenerEstadoRifa(Long id) {
        Rifa rifa = rifaRepository.findById(id).orElse(null);
        EstadoRifa estado = rifa != null ? rifa.getEstado() : EstadoRifa.EN_VENTA;
        Long torneoId = rifa != null && rifa.getTorneo() != null ? rifa.getTorneo().getId() : null;

        List<Integer> vendidos = numeroRepository.findByIdRifaIdAndEstado(id, EstadoNumero.VENDIDO)
                .stream()
                .map(n -> n.getId().getNumero())
                .toList();

        // lo vendido se reparte entre el pozo del torneo y los premios (HU-405)
        RepartoRifa reparto = rifa != null ? RepartoRifa.de(rifa, vendidos.size()) : null;
        BigDecimal pozoRifa = reparto != null ? reparto.aPremios() : BigDecimal.ZERO;
        BigDecimal aporteTorneo = reparto != null ? reparto.alPozoTorneo() : BigDecimal.ZERO;

        return new EventoRifaMessage(
                "SINCRONIZACION",
                id,
                torneoId,
                estado,
                vendidos,
                vendidos.size(),
                pozoRifa,
                aporteTorneo
        );
    }
}
