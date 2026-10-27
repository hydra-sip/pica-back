package com.hydra.pica.plataforma_pica.realtime.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.partido.dto.BracketTorneo;
import com.hydra.pica.plataforma_pica.partido.service.BracketService;
import com.hydra.pica.plataforma_pica.realtime.dto.EventoRifaMessage;
import com.hydra.pica.plataforma_pica.realtime.dto.EventoTorneoMessage;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoNumero;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Numero;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Ticket;
import com.hydra.pica.plataforma_pica.rifa.repository.NumeroRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.torneo.service.PozoTorneoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RealtimeWebSocketControllerTest {

    @Mock private TorneoRepository torneoRepository;
    @Mock private BracketService bracketService;
    @Mock private PozoTorneoService pozoTorneoService;
    @Mock private RifaRepository rifaRepository;
    @Mock private NumeroRepository numeroRepository;

    @InjectMocks
    private RealtimeWebSocketController controller;

    private Torneo torneo;
    private BracketTorneo bracket;
    private Rifa rifa;

    @BeforeEach
    void setUp() {
        torneo = new Torneo();
        torneo.setNombre("Torneo CS2");
        torneo.setEstado(EstadoTorneo.EN_CURSO);
        ReflectionTestUtils.setField(torneo, "id", 1L);

        bracket = new BracketTorneo(1L, "semilla", "hash", List.of());

        rifa = new Rifa();
        rifa.setTitulo("Rifa Aniversario");
        rifa.setEstado(EstadoRifa.EN_VENTA);
        rifa.setPrecioNumero(new BigDecimal("100.00"));
        rifa.setTorneo(torneo);
        ReflectionTestUtils.setField(rifa, "id", 10L);
    }

    @Test
    @DisplayName("Suscripción o sincronización de torneo devuelve estado completo para reconexión")
    void sincronizarTorneoDevuelveEstadoCompleto() {
        when(torneoRepository.findById(1L)).thenReturn(Optional.of(torneo));
        when(bracketService.ver(1L)).thenReturn(bracket);
        when(pozoTorneoService.calcularPozo(1L)).thenReturn(new BigDecimal("75000.00"));

        EventoTorneoMessage msg = controller.sincronizarTorneo(1L);

        assertThat(msg.tipo()).isEqualTo("SINCRONIZACION");
        assertThat(msg.torneoId()).isEqualTo(1L);
        assertThat(msg.estado()).isEqualTo(EstadoTorneo.EN_CURSO);
        assertThat(msg.bracket()).isEqualTo(bracket);
        assertThat(msg.pozo()).isEqualByComparingTo("75000.00");
        assertThat(msg.torneoFinalizado()).isFalse();

        // Verificamos que suscribirTorneo hace lo mismo
        EventoTorneoMessage suscripcionMsg = controller.suscribirTorneo(1L);
        assertThat(suscripcionMsg).isEqualTo(msg);
    }

    @Test
    @DisplayName("Suscripción o sincronización de rifa devuelve números vendidos y pozo")
    void sincronizarRifaDevuelveEstadoCompleto() {
        when(rifaRepository.findById(10L)).thenReturn(Optional.of(rifa));
        when(torneoRepository.findById(1L)).thenReturn(Optional.of(torneo));

        Numero num1 = Numero.vendido(rifa, 1, new Ticket());
        Numero num2 = Numero.vendido(rifa, 2, new Ticket());
        when(numeroRepository.findByIdRifaIdAndEstado(10L, EstadoNumero.VENDIDO)).thenReturn(List.of(num1, num2));

        EventoRifaMessage msg = controller.sincronizarRifa(10L);

        assertThat(msg.tipo()).isEqualTo("SINCRONIZACION");
        assertThat(msg.rifaId()).isEqualTo(10L);
        assertThat(msg.torneoId()).isEqualTo(1L);
        assertThat(msg.numeros()).containsExactly(1, 2);
        assertThat(msg.cantidadVendidos()).isEqualTo(2);
        assertThat(msg.pozoRifa()).isEqualByComparingTo("200.00");

        EventoRifaMessage suscripcionMsg = controller.suscribirRifa(10L);
        assertThat(suscripcionMsg).isEqualTo(msg);
    }
}
