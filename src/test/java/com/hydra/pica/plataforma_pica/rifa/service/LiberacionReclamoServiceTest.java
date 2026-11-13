package com.hydra.pica.plataforma_pica.rifa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.billetera.service.Acreditacion;
import com.hydra.pica.plataforma_pica.billetera.service.Acreditacion.Rol;
import com.hydra.pica.plataforma_pica.billetera.service.FondosEnCustodiaException;
import com.hydra.pica.plataforma_pica.billetera.service.LiquidacionRifaService;
import com.hydra.pica.plataforma_pica.billetera.service.LiquidacionRifaService.LiquidacionRifa;
import com.hydra.pica.plataforma_pica.billetera.service.Reintegrador;
import com.hydra.pica.plataforma_pica.billetera.service.Reintegrador.DestinatarioReintegro;
import com.hydra.pica.plataforma_pica.billetera.service.Reintegrador.ReintegroRifa;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.service.AuditoriaService;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoReclamo;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.ReclamoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.dto.ReclamoAdminDetalle;
import com.hydra.pica.plataforma_pica.rifa.event.FondosReclamoLiberados;
import com.hydra.pica.plataforma_pica.rifa.repository.NumeroRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.ReclamoRifaRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
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
class LiberacionReclamoServiceTest {

    @Mock
    private ReclamoRifaRepository reclamoRifaRepository;

    @Mock
    private RifaRepository rifaRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private NumeroRepository numeroRepository;

    @Mock
    private LiquidacionRifaService liquidacionRifaService;

    @Mock
    private Reintegrador reintegrador;

    @Mock
    private AuditoriaService auditoriaService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private LiberacionReclamoService service;

    private Usuario soporte;
    private Usuario reclamante;
    private Rifa rifa;
    private ReclamoRifa reclamo;

    @BeforeEach
    void setUp() {
        soporte = new Usuario();
        ReflectionTestUtils.setField(soporte, "id", 7L);
        soporte.setUsername("soporte_ana");

        reclamante = new Usuario();
        ReflectionTestUtils.setField(reclamante, "id", 42L);
        reclamante.setUsername("jperez");
        reclamante.setEmail("jperez@example.com");

        Torneo torneo = new Torneo();
        ReflectionTestUtils.setField(torneo, "id", 30L);
        torneo.setNombre("Copa UNLu");

        rifa = new Rifa();
        ReflectionTestUtils.setField(rifa, "id", 10L);
        rifa.setTitulo("Rifa Apertura");
        rifa.setTorneo(torneo);
        rifa.setEstado(EstadoRifa.SORTEADA);
        rifa.setSorteadaEn(Instant.parse("2026-11-10T12:00:00Z"));
        rifa.setSemillaObtenidaEn(Instant.parse("2026-11-10T12:00:05Z"));

        reclamo = new ReclamoRifa();
        ReflectionTestUtils.setField(reclamo, "id", 1L);
        reclamo.setRifa(rifa);
        reclamo.setUsuario(reclamante);
        reclamo.setMotivo("Irregularidad");
        reclamo.setEstado(EstadoReclamo.RECHAZADO);
        reclamo.setResolucion("Verificado");
        reclamo.setResueltoEn(Instant.parse("2026-11-11T10:00:00Z"));
        reclamo.setResueltoPor("soporte_ana");
        reclamo.setCreadoEn(Instant.parse("2026-11-10T15:00:00Z"));
    }

    @Test
    @DisplayName("Reclamo inexistente lanza 404 RECLAMO_NO_ENCONTRADO")
    void reclamoInexistenteLanza404() {
        when(usuarioRepository.findById(7L)).thenReturn(Optional.of(soporte));
        when(reclamoRifaRepository.findRifaIdById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.liberarFondos(7L, 99L))
                .isInstanceOfSatisfying(NoEncontradoException.class,
                        e -> assertThat(e.getCodigo()).isEqualTo(CodigoError.RECLAMO_NO_ENCONTRADO));

        verifyNoInteractions(liquidacionRifaService, reintegrador, auditoriaService, eventPublisher);
    }

    @Test
    @DisplayName("Liberar un reclamo PENDIENTE lanza 409 RECLAMO_NO_RESUELTO")
    void liberarReclamoPendienteLanza409() {
        reclamo.setEstado(EstadoReclamo.PENDIENTE);
        rifa.setEstado(EstadoRifa.EN_REVISION);
        stubBloqueos();

        assertThatThrownBy(() -> service.liberarFondos(7L, 1L))
                .isInstanceOfSatisfying(ConflictoException.class,
                        e -> assertThat(e.getCodigo()).isEqualTo(CodigoError.RECLAMO_NO_RESUELTO));

        verifyNoInteractions(liquidacionRifaService, reintegrador, auditoriaService, eventPublisher);
    }

    @Test
    @DisplayName("Reintentar liberar un reclamo ya liberado lanza 409 CUSTODIA_LIBERADA")
    void reintentarReclamoYaLiberadoLanza409() {
        reclamo.setFondosLiberadosEn(Instant.parse("2026-11-12T10:00:00Z"));
        reclamo.setFondosLiberadosPor("soporte_ana");
        reclamo.setFondosLiberadosMonto(new BigDecimal("1100.00"));
        stubBloqueos();

        assertThatThrownBy(() -> service.liberarFondos(7L, 1L))
                .isInstanceOfSatisfying(ConflictoException.class,
                        e -> assertThat(e.getCodigo()).isEqualTo(CodigoError.CUSTODIA_LIBERADA));

        verifyNoInteractions(liquidacionRifaService, reintegrador, auditoriaService, eventPublisher);
    }

    @Test
    @DisplayName("Liberar cuando quedan otros reclamos pendientes en la rifa lanza 409 FONDOS_EN_CUSTODIA")
    void liberarConOtrosPendientesLanza409() {
        rifa.setEstado(EstadoRifa.EN_REVISION);
        stubBloqueos();

        assertThatThrownBy(() -> service.liberarFondos(7L, 1L))
                .isInstanceOfSatisfying(FondosEnCustodiaException.class,
                        e -> assertThat(e.getCodigo()).isEqualTo(CodigoError.FONDOS_EN_CUSTODIA));

        verifyNoInteractions(liquidacionRifaService, reintegrador, auditoriaService, eventPublisher);
    }

    @Test
    @DisplayName("SIN_FRAUDE delega en liquidacionRifaService, registra auditoría completa y publica evento")
    void liberarSinFraudeLiquidaYAudita() {
        stubBloqueos();
        when(reclamoRifaRepository.existsByRifaIdAndEstado(10L, EstadoReclamo.PENDIENTE)).thenReturn(false);
        when(liquidacionRifaService.liquidarRifa(10L)).thenReturn(new LiquidacionRifa(
                10L,
                50L,
                new BigDecimal("1100.00"),
                List.of(
                        new Acreditacion(Rol.PREMIO, 88L, new BigDecimal("1000.00"), null, 1, "liq-r10-premio-1",
                                "Premio"),
                        new Acreditacion(Rol.PLATAFORMA, null, new BigDecimal("100.00"), null, null,
                                "liq-r10-plataforma", "Gastos")),
                BigDecimal.ZERO,
                false));
        when(reclamoRifaRepository.saveAndFlush(any(ReclamoRifa.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reclamoRifaRepository.findDetalleById(1L)).thenAnswer(inv -> {
            rifa.setEstado(EstadoRifa.LIQUIDADA);
            return Optional.of(reclamo);
        });

        ReclamoAdminDetalle detalle = service.liberarFondos(7L, 1L);

        assertThat(detalle.fondosLiberadosPor()).isEqualTo("soporte_ana");
        assertThat(detalle.fondosLiberadosMonto()).isEqualByComparingTo("1100.00");
        assertThat(detalle.fondosLiberadosEn()).isNotNull();
        assertThat(detalle.rifa().estado()).isEqualTo(EstadoRifa.LIQUIDADA);
        verifyNoInteractions(reintegrador);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> auditCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditoriaService).registrar(eq(7L), eq(AccionAuditoria.LIQUIDACION), eq("RECLAMO_RIFA"), eq("1"),
                auditCaptor.capture());
        assertThat(auditCaptor.getValue())
                .containsEntry("rifaId", 10L)
                .containsEntry("torneoId", 30L)
                .containsEntry("monto", new BigDecimal("1100.00"))
                .containsEntry("destino", "LIQUIDACION_GANADORES")
                .containsEntry("ejecutadoPor", "soporte_ana");

        ArgumentCaptor<FondosReclamoLiberados> eventCaptor = ArgumentCaptor.forClass(FondosReclamoLiberados.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().rifaEstadoFinal()).isEqualTo(EstadoRifa.LIQUIDADA);
    }

    @Test
    @DisplayName("FRAUDE_CONFIRMADO cancela rifa, marca números REINTEGRADO, reintegra a compradores y audita")
    void liberarConFraudeReintegraYAudita() {
        reclamo.setEstado(EstadoReclamo.ACEPTADO);
        stubBloqueos();
        when(reclamoRifaRepository.existsByRifaIdAndEstado(10L, EstadoReclamo.PENDIENTE)).thenReturn(false);
        when(rifaRepository.saveAndFlush(any(Rifa.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reintegrador.reintegrarRifa(10L, "Rifa Apertura")).thenReturn(new ReintegroRifa(
                new BigDecimal("1100.00"),
                List.of(
                        new DestinatarioReintegro(42L, 100L, new BigDecimal("550.00")),
                        new DestinatarioReintegro(43L, 101L, new BigDecimal("550.00")))));
        when(reclamoRifaRepository.saveAndFlush(any(ReclamoRifa.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reclamoRifaRepository.findDetalleById(1L)).thenReturn(Optional.of(reclamo));

        ReclamoAdminDetalle detalle = service.liberarFondos(7L, 1L);

        assertThat(rifa.getEstado()).isEqualTo(EstadoRifa.CANCELADA);
        assertThat(detalle.rifa().estado()).isEqualTo(EstadoRifa.CANCELADA);
        assertThat(detalle.fondosLiberadosPor()).isEqualTo("soporte_ana");
        assertThat(detalle.fondosLiberadosMonto()).isEqualByComparingTo("1100.00");
        verify(numeroRepository).reintegrarVendidos(10L);
        verifyNoInteractions(liquidacionRifaService);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> auditCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditoriaService).registrar(eq(7L), eq(AccionAuditoria.REINTEGRO), eq("RECLAMO_RIFA"), eq("1"),
                auditCaptor.capture());
        assertThat(auditCaptor.getValue())
                .containsEntry("rifaId", 10L)
                .containsEntry("torneoId", 30L)
                .containsEntry("monto", new BigDecimal("1100.00"))
                .containsEntry("destino", "REINTEGRO_COMPRADORES")
                .containsEntry("reclamanteId", 42L)
                .containsEntry("ejecutadoPor", "soporte_ana");

        verify(eventPublisher).publishEvent(new FondosReclamoLiberados(1L, 10L, 30L, EstadoRifa.CANCELADA));
    }

    @Test
    @DisplayName("Si ya hay rifa CANCELADA o LIQUIDADA responde 409 CUSTODIA_LIBERADA sin mutar nada")
    void rifaYaCanceladaResponde409() {
        rifa.setEstado(EstadoRifa.CANCELADA);
        stubBloqueos();

        assertThatThrownBy(() -> service.liberarFondos(7L, 1L))
                .isInstanceOfSatisfying(ConflictoException.class,
                        e -> assertThat(e.getCodigo()).isEqualTo(CodigoError.CUSTODIA_LIBERADA));

        verify(auditoriaService, never()).registrar(any(), any(), any(), any(), anyMap());
    }

    private void stubBloqueos() {
        when(usuarioRepository.findById(7L)).thenReturn(Optional.of(soporte));
        when(reclamoRifaRepository.findRifaIdById(1L)).thenReturn(Optional.of(10L));
        when(rifaRepository.buscarBloqueado(10L)).thenReturn(Optional.of(rifa));
        when(reclamoRifaRepository.buscarBloqueado(1L)).thenReturn(Optional.of(reclamo));
    }
}

