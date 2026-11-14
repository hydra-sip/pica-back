package com.hydra.pica.plataforma_pica.rifa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.service.AuditoriaService;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoReclamo;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.ReclamoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.dto.DecisionReclamo;
import com.hydra.pica.plataforma_pica.rifa.dto.ReclamoAdminDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.ResolverReclamoRequest;
import com.hydra.pica.plataforma_pica.rifa.event.ReclamoRifaResuelto;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReclamoAdminServiceTest {

    @Mock
    private ReclamoRifaRepository reclamoRifaRepository;

    @Mock
    private RifaRepository rifaRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private AuditoriaService auditoriaService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ReclamoAdminService service;

    private Usuario soporte;
    private Usuario reclamante;
    private Rifa rifa;
    private ReclamoRifa reclamo;

    @BeforeEach
    void setUp() {
        soporte = new Usuario();
        ReflectionTestUtils.setField(soporte, "id", 7L);
        soporte.setUsername("soporte_ana");
        soporte.setEmail("soporte@pica.com");

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
        rifa.setEstado(EstadoRifa.EN_REVISION);
        rifa.setSorteadaEn(Instant.parse("2026-11-10T12:00:00Z"));
        rifa.setSemillaObtenidaEn(Instant.parse("2026-11-10T12:00:05Z"));

        reclamo = new ReclamoRifa();
        ReflectionTestUtils.setField(reclamo, "id", 1L);
        reclamo.setRifa(rifa);
        reclamo.setUsuario(reclamante);
        reclamo.setMotivo("Número ganador incorrecto");
        reclamo.setEstado(EstadoReclamo.PENDIENTE);
        reclamo.setCreadoEn(Instant.parse("2026-11-10T15:00:00Z"));
    }

    @Test
    @DisplayName("listar delega en el query general si no hay filtro y filtra por estado si se informa")
    void listarConYSinFiltro() {
        PageRequest pageable = PageRequest.of(0, 20);
        when(reclamoRifaRepository.buscarTodosParaAdmin(pageable))
                .thenReturn(new PageImpl<>(List.of(reclamo), pageable, 1));
        when(reclamoRifaRepository.buscarPorEstadoParaAdmin(EstadoReclamo.PENDIENTE, pageable))
                .thenReturn(new PageImpl<>(List.of(reclamo), pageable, 1));

        Page<ReclamoAdminDetalle> sinFiltro = service.listar(null, pageable);
        Page<ReclamoAdminDetalle> conFiltro = service.listar(EstadoReclamo.PENDIENTE, pageable);

        assertThat(sinFiltro.getContent()).singleElement().satisfies(d -> {
            assertThat(d.id()).isEqualTo(1L);
            assertThat(d.rifa().torneoNombre()).isEqualTo("Copa UNLu");
            assertThat(d.reclamante().username()).isEqualTo("jperez");
        });
        assertThat(conFiltro.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("detalle inexistente lanza 404 RECLAMO_NO_ENCONTRADO")
    void detalleInexistenteLanza404() {
        when(reclamoRifaRepository.findDetalleById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detalle(99L))
                .isInstanceOfSatisfying(NoEncontradoException.class,
                        e -> assertThat(e.getCodigo()).isEqualTo(CodigoError.RECLAMO_NO_ENCONTRADO));
    }

    @Test
    @DisplayName("resolver sin decisión o sin resolución escrita lanza 400 VALIDACION")
    void resolverSinCamposObligatoriosLanza400() {
        assertThatThrownBy(() -> service.resolver(7L, 1L, new ResolverReclamoRequest(null, "   ")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getCodigo()).isEqualTo(CodigoError.VALIDACION);
                });

        verifyNoInteractions(reclamoRifaRepository, rifaRepository, auditoriaService, eventPublisher);
    }

    @Test
    @DisplayName("resolver reclamo ya resuelto lanza 409 RECLAMO_YA_RESUELTO sin modificar ni auditar")
    void resolverYaResueltoLanza409() {
        reclamo.setEstado(EstadoReclamo.ACEPTADO);
        when(usuarioRepository.findById(7L)).thenReturn(Optional.of(soporte));
        when(reclamoRifaRepository.findRifaIdById(1L)).thenReturn(Optional.of(10L));
        when(rifaRepository.buscarBloqueado(10L)).thenReturn(Optional.of(rifa));
        when(reclamoRifaRepository.buscarBloqueado(1L)).thenReturn(Optional.of(reclamo));

        assertThatThrownBy(() -> service.resolver(
                7L, 1L, new ResolverReclamoRequest(DecisionReclamo.SIN_FRAUDE, "Intento de reapertura")))
                .isInstanceOfSatisfying(ConflictoException.class,
                        e -> assertThat(e.getCodigo()).isEqualTo(CodigoError.RECLAMO_YA_RESUELTO));

        verify(reclamoRifaRepository, never()).saveAndFlush(any());
        verifyNoInteractions(auditoriaService, eventPublisher);
    }

    @Test
    @DisplayName("resolver SIN_FRAUDE como único reclamo pasa la rifa a SORTEADA, audita y publica evento")
    void resolverUltimoReclamoSacaRifaDeRevision() {
        when(usuarioRepository.findById(7L)).thenReturn(Optional.of(soporte));
        when(reclamoRifaRepository.findRifaIdById(1L)).thenReturn(Optional.of(10L));
        when(rifaRepository.buscarBloqueado(10L)).thenReturn(Optional.of(rifa));
        when(reclamoRifaRepository.buscarBloqueado(1L)).thenReturn(Optional.of(reclamo));
        when(reclamoRifaRepository.saveAndFlush(any(ReclamoRifa.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reclamoRifaRepository.existsByRifaIdAndEstado(10L, EstadoReclamo.PENDIENTE)).thenReturn(false);
        when(rifaRepository.saveAndFlush(any(Rifa.class))).thenAnswer(inv -> inv.getArgument(0));

        ReclamoAdminDetalle resultado = service.resolver(
                7L, 1L, new ResolverReclamoRequest(DecisionReclamo.SIN_FRAUDE, "  Semilla validada  "));

        assertThat(resultado.estado()).isEqualTo(EstadoReclamo.RECHAZADO);
        assertThat(resultado.resolucion()).isEqualTo("Semilla validada");
        assertThat(resultado.resueltoPor()).isEqualTo("soporte_ana");
        assertThat(resultado.resueltoEn()).isNotNull();
        assertThat(resultado.rifa().estado()).isEqualTo(EstadoRifa.SORTEADA);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> auditoriaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditoriaService).registrar(eq(7L), eq(AccionAuditoria.LAUDO), eq("RECLAMO_RIFA"), eq("1"),
                auditoriaCaptor.capture());
        assertThat(auditoriaCaptor.getValue())
                .containsEntry("decision", "SIN_FRAUDE")
                .containsEntry("estado", "RECHAZADO")
                .containsEntry("rifaEstadoFinal", "SORTEADA");

        ArgumentCaptor<ReclamoRifaResuelto> eventoCaptor = ArgumentCaptor.forClass(ReclamoRifaResuelto.class);
        verify(eventPublisher).publishEvent(eventoCaptor.capture());
        assertThat(eventoCaptor.getValue().rifaSalioDeRevision()).isTrue();
        assertThat(eventoCaptor.getValue().estadoResultante()).isEqualTo(EstadoReclamo.RECHAZADO);
    }

    @Test
    @DisplayName("resolver FRAUDE_CONFIRMADO cuando aún quedan otros reclamos PENDIENTE mantiene la rifa EN_REVISION")
    void resolverConOtrosPendientesMantieneEnRevision() {
        when(usuarioRepository.findById(7L)).thenReturn(Optional.of(soporte));
        when(reclamoRifaRepository.findRifaIdById(1L)).thenReturn(Optional.of(10L));
        when(rifaRepository.buscarBloqueado(10L)).thenReturn(Optional.of(rifa));
        when(reclamoRifaRepository.buscarBloqueado(1L)).thenReturn(Optional.of(reclamo));
        when(reclamoRifaRepository.saveAndFlush(any(ReclamoRifa.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reclamoRifaRepository.existsByRifaIdAndEstado(10L, EstadoReclamo.PENDIENTE)).thenReturn(true);

        ReclamoAdminDetalle resultado = service.resolver(
                7L, 1L, new ResolverReclamoRequest(DecisionReclamo.FRAUDE_CONFIRMADO, "Lista alterada"));

        assertThat(resultado.estado()).isEqualTo(EstadoReclamo.ACEPTADO);
        assertThat(resultado.rifa().estado()).isEqualTo(EstadoRifa.EN_REVISION);
        verify(rifaRepository, never()).saveAndFlush(any());

        ArgumentCaptor<ReclamoRifaResuelto> eventoCaptor = ArgumentCaptor.forClass(ReclamoRifaResuelto.class);
        verify(eventPublisher).publishEvent(eventoCaptor.capture());
        assertThat(eventoCaptor.getValue().rifaSalioDeRevision()).isFalse();
        assertThat(eventoCaptor.getValue().decision()).isEqualTo(DecisionReclamo.FRAUDE_CONFIRMADO);
    }
}

