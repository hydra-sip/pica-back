package com.hydra.pica.plataforma_pica.rifa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.error.ProhibidoException;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoReclamo;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.ReclamoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.dto.ReclamoRifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.ReclamoRifaRequest;
import com.hydra.pica.plataforma_pica.rifa.event.ReclamoRifaRegistrado;
import com.hydra.pica.plataforma_pica.rifa.repository.ReclamoRifaRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.TicketRepository;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReclamoRifaServiceTest {

    @Mock
    private RifaRepository rifaRepository;

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private ReclamoRifaRepository reclamoRifaRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ReclamoRifaService reclamoRifaService;

    private Usuario organizador;
    private Usuario comprador;
    private Rifa rifa;

    @BeforeEach
    void setUp() {
        organizador = new Usuario();
        ReflectionTestUtils.setField(organizador, "id", 10L);
        organizador.setEmail("organizador@rifa.com");
        organizador.setUsername("organizador");

        comprador = new Usuario();
        ReflectionTestUtils.setField(comprador, "id", 55L);
        comprador.setEmail("comprador@gmail.com");
        comprador.setUsername("comprador");

        rifa = new Rifa();
        ReflectionTestUtils.setField(rifa, "id", 100L);
        rifa.setTitulo("Gran Rifa Pro Fondos");
        rifa.setOrganizador(organizador);
        rifa.setNumeroDesde(1);
        rifa.setNumeroHasta(100);
        rifa.setPrecioNumero(BigDecimal.valueOf(1000));
        rifa.setEstado(EstadoRifa.SORTEADA);
        rifa.setSemillaObtenidaEn(Instant.now().minus(2, ChronoUnit.HOURS));
        rifa.setSorteadaEn(Instant.now().minus(2, ChronoUnit.HOURS));
    }

    @Test
    @DisplayName("Criterio 1: Intentar reclamar pasadas las 48 horas de finalizada indica error PLAZO_RECLAMO_VENCIDO")
    void reclamarPasadas48HorasLanzaConflicto() {
        // Finalizada hace 49 horas
        Instant sorteoHace49Horas = Instant.now().minus(49, ChronoUnit.HOURS);
        rifa.setSemillaObtenidaEn(sorteoHace49Horas);
        rifa.setSorteadaEn(sorteoHace49Horas);

        when(usuarioRepository.findById(55L)).thenReturn(Optional.of(comprador));
        when(rifaRepository.buscarBloqueado(100L)).thenReturn(Optional.of(rifa));
        when(ticketRepository.existsByRifaIdAndCompradorId(100L, 55L)).thenReturn(true);

        assertThatThrownBy(() -> reclamoRifaService.registrarReclamo(
                55L, 100L, new ReclamoRifaRequest("Irregularidad en sorteo")))
                .isInstanceOf(ConflictoException.class)
                .hasMessageContaining("Venció el plazo de 48 horas")
                .matches(e -> ((ConflictoException) e).getCodigo() == CodigoError.PLAZO_RECLAMO_VENCIDO);

        verify(reclamoRifaRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("Criterio 2: Intentar reclamar sin haber comprado un ticket de esa rifa indica error NO_ES_COMPRADOR")
    void reclamarSinSerCompradorLanzaProhibido() {
        when(usuarioRepository.findById(55L)).thenReturn(Optional.of(comprador));
        when(rifaRepository.buscarBloqueado(100L)).thenReturn(Optional.of(rifa));
        // No compró tickets
        when(ticketRepository.existsByRifaIdAndCompradorId(100L, 55L)).thenReturn(false);

        assertThatThrownBy(() -> reclamoRifaService.registrarReclamo(
                55L, 100L, new ReclamoRifaRequest("Sospecho del resultado")))
                .isInstanceOf(ProhibidoException.class)
                .hasMessageContaining("Solo los compradores")
                .matches(e -> ((ProhibidoException) e).getCodigo() == CodigoError.NO_ES_COMPRADOR);

        verify(reclamoRifaRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("Criterio 3 y 4: Reclamar dentro de las 48 horas registra el reclamo, pasa la rifa a EN_REVISION y avisa al organizador")
    void reclamarDentroDeVentanaExitoso() {
        when(usuarioRepository.findById(55L)).thenReturn(Optional.of(comprador));
        when(rifaRepository.buscarBloqueado(100L)).thenReturn(Optional.of(rifa));
        when(ticketRepository.existsByRifaIdAndCompradorId(100L, 55L)).thenReturn(true);
        when(reclamoRifaRepository.save(any(ReclamoRifa.class))).thenAnswer(inv -> {
            ReclamoRifa r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", 1L);
            return r;
        });

        ReclamoRifaDetalle resultado = reclamoRifaService.registrarReclamo(
                55L, 100L, new ReclamoRifaRequest("El número ganador no coincide con la semilla pública"));

        // Criterio 3: Reclamo registrado
        assertThat(resultado.id()).isEqualTo(1L);
        assertThat(resultado.rifaId()).isEqualTo(100L);
        assertThat(resultado.usuarioId()).isEqualTo(55L);
        assertThat(resultado.motivo()).isEqualTo("El número ganador no coincide con la semilla pública");
        assertThat(resultado.estado()).isEqualTo(EstadoReclamo.PENDIENTE);

        // Criterio 4: La rifa pasa a EN_REVISION
        assertThat(rifa.getEstado()).isEqualTo(EstadoRifa.EN_REVISION);
        verify(rifaRepository).save(rifa);

        // Notificación al organizador vía evento
        ArgumentCaptor<ReclamoRifaRegistrado> eventoCaptor = ArgumentCaptor.forClass(ReclamoRifaRegistrado.class);
        verify(eventPublisher).publishEvent(eventoCaptor.capture());
        ReclamoRifaRegistrado evento = eventoCaptor.getValue();
        assertThat(evento.rifaId()).isEqualTo(100L);
        assertThat(evento.reclamoId()).isEqualTo(1L);
        assertThat(evento.usuarioId()).isEqualTo(55L);
        assertThat(evento.motivo()).contains("El número ganador no coincide");
    }

    @Test
    @DisplayName("Intentar reclamar una rifa que aún no fue sorteada (EN_VENTA) indica error RIFA_NO_SORTEADA")
    void reclamarRifaNoSorteadaLanzaConflicto() {
        rifa.setEstado(EstadoRifa.EN_VENTA);
        when(usuarioRepository.findById(55L)).thenReturn(Optional.of(comprador));
        when(rifaRepository.buscarBloqueado(100L)).thenReturn(Optional.of(rifa));
        when(ticketRepository.existsByRifaIdAndCompradorId(100L, 55L)).thenReturn(true);

        assertThatThrownBy(() -> reclamoRifaService.registrarReclamo(
                55L, 100L, new ReclamoRifaRequest("Aún no sorteó")))
                .isInstanceOf(ConflictoException.class)
                .hasMessageContaining("aún no ha finalizado su sorteo")
                .matches(e -> ((ConflictoException) e).getCodigo() == CodigoError.RIFA_NO_SORTEADA);
    }

    @Test
    @DisplayName("Intentar reclamar con motivo vacío lanza error de validación 400")
    void reclamarSinMotivoLanzaValidacion() {
        assertThatThrownBy(() -> reclamoRifaService.registrarReclamo(55L, 100L, new ReclamoRifaRequest("   ")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Hay campos inválidos");

        assertThatThrownBy(() -> reclamoRifaService.registrarReclamo(55L, 100L, new ReclamoRifaRequest(null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("Intentar reclamar rifa inexistente lanza 404 RIFA_NO_ENCONTRADA")
    void reclamarRifaInexistenteLanza404() {
        when(usuarioRepository.findById(55L)).thenReturn(Optional.of(comprador));
        when(rifaRepository.buscarBloqueado(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reclamoRifaService.registrarReclamo(
                55L, 999L, new ReclamoRifaRequest("Rifa que no existe")))
                .isInstanceOf(NoEncontradoException.class)
                .hasMessageContaining("No existe la rifa");
    }
}
