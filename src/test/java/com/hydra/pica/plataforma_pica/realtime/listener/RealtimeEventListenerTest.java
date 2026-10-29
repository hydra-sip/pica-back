package com.hydra.pica.plataforma_pica.realtime.listener;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RealtimeEventListenerTest {

    @Mock private RealtimeNotifier realtimeNotifier;
    @Mock private BracketService bracketService;
    @Mock private TorneoRepository torneoRepository;
    @Mock private PozoTorneoService pozoTorneoService;
    @Mock private RifaRepository rifaRepository;
    @Mock private NumeroRepository numeroRepository;

    @InjectMocks
    private RealtimeEventListener listener;

    private Torneo torneo;
    private BracketTorneo bracket;

    @BeforeEach
    void setUp() {
        torneo = new Torneo();
        torneo.setNombre("Torneo CS2");
        torneo.setEstado(EstadoTorneo.EN_CURSO);

        bracket = new BracketTorneo(1L, "semilla-test", "hash-test", List.of());
    }

    @Test
    @DisplayName("Confirmar resultado de partido publica avance de llave a /topic/torneos/{id}")
    void confirmarResultadoPublicaEnTopicTorneos() {
        when(torneoRepository.findById(1L)).thenReturn(Optional.of(torneo));
        when(bracketService.ver(1L)).thenReturn(bracket);
        when(pozoTorneoService.calcularPozo(1L)).thenReturn(new BigDecimal("50000.00"));

        ResultadoConfirmado evento = new ResultadoConfirmado(1L, 10L, 100L, false);
        listener.onResultadoConfirmado(evento);

        ArgumentCaptor<EventoTorneoMessage> captor = ArgumentCaptor.forClass(EventoTorneoMessage.class);
        verify(realtimeNotifier).publicarTorneo(eq(1L), captor.capture());

        EventoTorneoMessage msg = captor.getValue();
        assertThat(msg.tipo()).isEqualTo("RESULTADO_CONFIRMADO");
        assertThat(msg.torneoId()).isEqualTo(1L);
        assertThat(msg.partidoId()).isEqualTo(10L);
        assertThat(msg.ganadorId()).isEqualTo(100L);
        assertThat(msg.torneoFinalizado()).isFalse();
        assertThat(msg.estado()).isEqualTo(EstadoTorneo.EN_CURSO);
        assertThat(msg.bracket()).isEqualTo(bracket);
        assertThat(msg.pozo()).isEqualByComparingTo("50000.00");
    }

    @Test
    @DisplayName("Confirmar la final publica estado FINALIZADO a /topic/torneos/{id}")
    void confirmarFinalPublicaTorneoFinalizado() {
        when(bracketService.ver(1L)).thenReturn(bracket);
        when(pozoTorneoService.calcularPozo(1L)).thenReturn(new BigDecimal("60000.00"));

        ResultadoConfirmado evento = new ResultadoConfirmado(1L, 15L, 100L, true);
        listener.onResultadoConfirmado(evento);

        ArgumentCaptor<EventoTorneoMessage> captor = ArgumentCaptor.forClass(EventoTorneoMessage.class);
        verify(realtimeNotifier).publicarTorneo(eq(1L), captor.capture());

        EventoTorneoMessage msg = captor.getValue();
        assertThat(msg.tipo()).isEqualTo("RESULTADO_CONFIRMADO");
        assertThat(msg.torneoFinalizado()).isTrue();
        assertThat(msg.estado()).isEqualTo(EstadoTorneo.FINALIZADO);
    }

    @Test
    @DisplayName("Cambio de estado general de torneo se publica a /topic/torneos/{id}")
    void cambioEstadoTorneoPublicaEnTopic() {
        when(bracketService.ver(1L)).thenReturn(bracket);
        when(pozoTorneoService.calcularPozo(1L)).thenReturn(new BigDecimal("40000.00"));

        TorneoEstadoCambiado evento = new TorneoEstadoCambiado(1L, EstadoTorneo.INSCRIPCION_CERRADA, EstadoTorneo.EN_CURSO);
        listener.onTorneoEstadoCambiado(evento);

        ArgumentCaptor<EventoTorneoMessage> captor = ArgumentCaptor.forClass(EventoTorneoMessage.class);
        verify(realtimeNotifier).publicarTorneo(eq(1L), captor.capture());

        EventoTorneoMessage msg = captor.getValue();
        assertThat(msg.tipo()).isEqualTo("ESTADO_CAMBIADO");
        assertThat(msg.estado()).isEqualTo(EstadoTorneo.EN_CURSO);
        assertThat(msg.torneoFinalizado()).isFalse();
    }

    @Test
    @DisplayName("Venta de números publica en /topic/rifas/{id} y actualiza el pozo en /topic/torneos/{id}")
    void ventaNumerosPublicaEnRifaYTorneo() {
        Rifa rifa = new Rifa();
        rifa.setTitulo("Rifa de Oro");
        rifa.setEstado(EstadoRifa.EN_VENTA);
        rifa.setPrecioNumero(new BigDecimal("100.00"));
        rifa.setTorneo(torneo);
        rifa.setPctDestinadoTorneo(10);

        when(rifaRepository.findById(5L)).thenReturn(Optional.of(rifa));
        when(numeroRepository.countByIdRifaIdAndEstado(5L, EstadoNumero.VENDIDO)).thenReturn(25L);
        when(torneoRepository.findById(1L)).thenReturn(Optional.of(torneo));
        when(bracketService.ver(1L)).thenReturn(bracket);

        NumerosVendidos evento = new NumerosVendidos(
                5L,
                1L,
                List.of(7, 8, 9),
                42L,
                new BigDecimal("300.00"),
                new BigDecimal("2500.00"),
                new BigDecimal("52500.00")
        );

        listener.onNumerosVendidos(evento);

        // Verificamos publicación a /topic/rifas/5
        ArgumentCaptor<EventoRifaMessage> rifaCaptor = ArgumentCaptor.forClass(EventoRifaMessage.class);
        verify(realtimeNotifier).publicarRifa(eq(5L), rifaCaptor.capture());
        EventoRifaMessage rifaMsg = rifaCaptor.getValue();
        assertThat(rifaMsg.tipo()).isEqualTo("NUMEROS_VENDIDOS");
        assertThat(rifaMsg.rifaId()).isEqualTo(5L);
        assertThat(rifaMsg.torneoId()).isEqualTo(1L);
        assertThat(rifaMsg.numeros()).containsExactly(7, 8, 9);
        assertThat(rifaMsg.cantidadVendidos()).isEqualTo(25);
        assertThat(rifaMsg.pozoRifa()).isEqualByComparingTo("2500.00");
        // el 10 % que la rifa destina al torneo
        assertThat(rifaMsg.aporteTorneo()).isEqualByComparingTo("250.00");

        // Verificamos publicación a /topic/torneos/1
        ArgumentCaptor<EventoTorneoMessage> torneoCaptor = ArgumentCaptor.forClass(EventoTorneoMessage.class);
        verify(realtimeNotifier).publicarTorneo(eq(1L), torneoCaptor.capture());
        EventoTorneoMessage torneoMsg = torneoCaptor.getValue();
        assertThat(torneoMsg.tipo()).isEqualTo("POZO_ACTUALIZADO");
        assertThat(torneoMsg.torneoId()).isEqualTo(1L);
        assertThat(torneoMsg.pozo()).isEqualByComparingTo("52500.00");
    }

    @Test
    @DisplayName("Resultado objetado se publica en /topic/torneos/{id}")
    void resultadoObjetadoPublicaEnTopic() {
        when(torneoRepository.findById(1L)).thenReturn(Optional.of(torneo));
        when(bracketService.ver(1L)).thenReturn(bracket);
        when(pozoTorneoService.calcularPozo(1L)).thenReturn(new BigDecimal("50000.00"));

        ResultadoObjetado evento = new ResultadoObjetado(1L, 10L);
        listener.onResultadoObjetado(evento);

        ArgumentCaptor<EventoTorneoMessage> captor = ArgumentCaptor.forClass(EventoTorneoMessage.class);
        verify(realtimeNotifier).publicarTorneo(eq(1L), captor.capture());

        EventoTorneoMessage msg = captor.getValue();
        assertThat(msg.tipo()).isEqualTo("RESULTADO_OBJETADO");
        assertThat(msg.partidoId()).isEqualTo(10L);
    }
}
