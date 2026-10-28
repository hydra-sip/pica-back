package com.hydra.pica.plataforma_pica.realtime.listener;

import java.math.BigDecimal;

import com.hydra.pica.plataforma_pica.partido.dto.BracketTorneo;
import com.hydra.pica.plataforma_pica.partido.event.ResultadoConfirmado;
import com.hydra.pica.plataforma_pica.partido.event.ResultadoObjetado;
import com.hydra.pica.plataforma_pica.partido.service.BracketService;
import com.hydra.pica.plataforma_pica.realtime.dto.EventoRifaMessage;
import com.hydra.pica.plataforma_pica.realtime.dto.EventoTorneoMessage;
import com.hydra.pica.plataforma_pica.realtime.service.RealtimeNotifier;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoNumero;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.event.NumerosVendidos;
import com.hydra.pica.plataforma_pica.rifa.repository.NumeroRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.event.TorneoEstadoCambiado;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.torneo.service.PozoTorneoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Escucha eventos de dominio y publica actualizaciones por WebSocket a /topic/torneos/{id}
 * y /topic/rifas/{id} (HU-305, T305-1).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RealtimeEventListener {

    private final RealtimeNotifier realtimeNotifier;
    private final BracketService bracketService;
    private final TorneoRepository torneoRepository;
    private final PozoTorneoService pozoTorneoService;
    private final RifaRepository rifaRepository;
    private final NumeroRepository numeroRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onResultadoConfirmado(ResultadoConfirmado evento) {
        log.info("Resultado confirmado para partido {} en torneo {}. Finalizado: {}",
                evento.partidoId(), evento.torneoId(), evento.torneoFinalizado());

        BracketTorneo bracket = obtenerBracket(evento.torneoId());
        EstadoTorneo estado = evento.torneoFinalizado() ? EstadoTorneo.FINALIZADO : obtenerEstadoTorneo(evento.torneoId());
        BigDecimal pozo = pozoTorneoService.calcularPozo(evento.torneoId());

        EventoTorneoMessage mensaje = new EventoTorneoMessage(
                "RESULTADO_CONFIRMADO",
                evento.torneoId(),
                estado,
                evento.partidoId(),
                evento.ganadorId(),
                evento.torneoFinalizado(),
                bracket,
                pozo
        );

        realtimeNotifier.publicarTorneo(evento.torneoId(), mensaje);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onResultadoObjetado(ResultadoObjetado evento) {
        log.info("Resultado objetado para partido {} en torneo {}", evento.partidoId(), evento.torneoId());

        BracketTorneo bracket = obtenerBracket(evento.torneoId());
        EstadoTorneo estado = obtenerEstadoTorneo(evento.torneoId());
        BigDecimal pozo = pozoTorneoService.calcularPozo(evento.torneoId());

        EventoTorneoMessage mensaje = new EventoTorneoMessage(
                "RESULTADO_OBJETADO",
                evento.torneoId(),
                estado,
                evento.partidoId(),
                null,
                false,
                bracket,
                pozo
        );

        realtimeNotifier.publicarTorneo(evento.torneoId(), mensaje);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTorneoEstadoCambiado(TorneoEstadoCambiado evento) {
        log.info("Estado del torneo {} cambió a {}", evento.torneoId(), evento.estadoNuevo());

        BracketTorneo bracket = obtenerBracket(evento.torneoId());
        BigDecimal pozo = pozoTorneoService.calcularPozo(evento.torneoId());

        EventoTorneoMessage mensaje = new EventoTorneoMessage(
                "ESTADO_CAMBIADO",
                evento.torneoId(),
                evento.estadoNuevo(),
                null,
                null,
                evento.estadoNuevo() == EstadoTorneo.FINALIZADO,
                bracket,
                pozo
        );

        realtimeNotifier.publicarTorneo(evento.torneoId(), mensaje);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNumerosVendidos(NumerosVendidos evento) {
        log.info("Números vendidos para rifa {}: {}. Nuevo pozo rifa: {}",
                evento.rifaId(), evento.numeros(), evento.pozoRifa());

        Rifa rifa = rifaRepository.findById(evento.rifaId()).orElse(null);
        EstadoRifa estadoRifa = rifa != null ? rifa.getEstado() : EstadoRifa.EN_VENTA;

        int totalVendidos = (int) numeroRepository.countByIdRifaIdAndEstado(evento.rifaId(), EstadoNumero.VENDIDO);

        BigDecimal aporteTorneo = evento.torneoId() != null ? aporteRifa(rifa, evento.pozoRifa()) : BigDecimal.ZERO;

        EventoRifaMessage mensajeRifa = new EventoRifaMessage(
                "NUMEROS_VENDIDOS",
                evento.rifaId(),
                evento.torneoId(),
                estadoRifa,
                evento.numeros(),
                totalVendidos,
                evento.pozoRifa(),
                aporteTorneo
        );
        realtimeNotifier.publicarRifa(evento.rifaId(), mensajeRifa);

        if (evento.torneoId() != null) {
            log.info("Actualizando pozo del torneo {} en tiempo real a {}",
                    evento.torneoId(), evento.pozoTorneo());

            BracketTorneo bracket = obtenerBracket(evento.torneoId());
            EstadoTorneo estadoTorneo = obtenerEstadoTorneo(evento.torneoId());

            EventoTorneoMessage mensajeTorneo = new EventoTorneoMessage(
                    "POZO_ACTUALIZADO",
                    evento.torneoId(),
                    estadoTorneo,
                    null,
                    null,
                    estadoTorneo == EstadoTorneo.FINALIZADO,
                    bracket,
                    evento.pozoTorneo()
            );
            realtimeNotifier.publicarTorneo(evento.torneoId(), mensajeTorneo);
        }
    }

    private BracketTorneo obtenerBracket(Long torneoId) {
        try {
            return bracketService.ver(torneoId);
        } catch (Exception e) {
            log.debug("No se pudo obtener el bracket para el torneo {}: {}", torneoId, e.getMessage());
            return null;
        }
    }

    private EstadoTorneo obtenerEstadoTorneo(Long torneoId) {
        return torneoRepository.findById(torneoId)
                .map(Torneo::getEstado)
                .orElse(null);
    }

    /** El % de lo vendido que la rifa destina al pozo de su torneo (HU-401). */
    private BigDecimal aporteRifa(Rifa rifa, BigDecimal pozoRifa) {
        if (rifa == null || pozoRifa == null) {
            return BigDecimal.ZERO;
        }
        return pozoRifa.multiply(BigDecimal.valueOf(rifa.getPctDestinadoTorneo()))
                .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
    }
}
