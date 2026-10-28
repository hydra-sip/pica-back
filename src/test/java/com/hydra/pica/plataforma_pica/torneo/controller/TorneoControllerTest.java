package com.hydra.pica.plataforma_pica.torneo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionDelTorneo;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionDetalle.EquipoInscripto;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Formato;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.dto.CheckinTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.InformacionTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.MiTorneoResumen;
import com.hydra.pica.plataforma_pica.torneo.dto.MisTorneos;
import com.hydra.pica.plataforma_pica.torneo.dto.OpcionesTorneo;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoInformacion;
import com.hydra.pica.plataforma_pica.torneo.service.InscripcionTorneoService;
import com.hydra.pica.plataforma_pica.torneo.service.MisTorneosService;
import com.hydra.pica.plataforma_pica.torneo.service.TorneoService;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioPublico;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel.PageMetadata;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** /api/v1/torneos del organizador (PICA-199 y PICA-201): el id sale de la sesión y las reglas están en los servicios. */
@WebMvcTest(TorneoController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class TorneoControllerTest {

    private static final Long ORGANIZADOR = 5L;
    private static final Instant CREADO = Instant.parse("2026-10-25T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TorneoService torneoService;

    @MockitoBean
    private MisTorneosService misTorneosService;

    @MockitoBean
    private InscripcionTorneoService inscripcionTorneoService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @DisplayName("Sin autenticación responde 401")
    void sinAutenticacion() throws Exception {
        mockMvc.perform(get("/api/v1/torneos/opciones"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(torneoService);
    }

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("Sin TORNEO_GESTIONAR (no es organizador) responde 403 SIN_PERMISO")
    void sinPermiso() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));

        mockMvc.perform(post("/api/v1/torneos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre": "Apertura F5", "disciplina": "Fútbol 5"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
        mockMvc.perform(get("/api/v1/torneos/opciones"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(torneoService);
    }

    @Test
    @WithMockUser(authorities = "TORNEO_GESTIONAR")
    @DisplayName("POST /api/v1/torneos crea el borrador con el usuario de la sesión y responde 201 con Location")
    void crea() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));
        when(torneoService.crear(eq(ORGANIZADOR), any(InformacionTorneoRequest.class))).thenReturn(borrador());

        mockMvc.perform(post("/api/v1/torneos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre": "Apertura F5", "disciplina": "Fútbol 5", "modalidad": "PRESENCIAL",
                                 "sede": "Club Mercedes", "canchas": ["Cancha 1"]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/torneos/9"))
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.estado").value("BORRADOR"))
                .andExpect(jsonPath("$.canchas[0]").value("Cancha 1"));

        ArgumentCaptor<InformacionTorneoRequest> captor = ArgumentCaptor.forClass(InformacionTorneoRequest.class);
        verify(torneoService).crear(eq(ORGANIZADOR), captor.capture());
        assertThat(captor.getValue()).isEqualTo(new InformacionTorneoRequest("Apertura F5", "Fútbol 5", null, null,
                Modalidad.PRESENCIAL, null, "Club Mercedes", List.of("Cancha 1"), null, null, null, null, null, null,
                null, null));
    }

    @Test
    @WithMockUser(authorities = "TORNEO_GESTIONAR")
    @DisplayName("PUT /api/v1/torneos/{id}/informacion no ignora lo que no es de los pasos 1 y 2: se lo pasa al servicio")
    void noIgnoraLaParteEconomica() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));
        when(torneoService.actualizarInformacion(eq(ORGANIZADOR), eq(9L), any(InformacionTorneoRequest.class)))
                .thenReturn(borrador());

        mockMvc.perform(put("/api/v1/torneos/9/informacion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre": "Apertura F5", "disciplina": "Fútbol 5", "valorInscripcion": 5000}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<InformacionTorneoRequest> captor = ArgumentCaptor.forClass(InformacionTorneoRequest.class);
        verify(torneoService).actualizarInformacion(eq(ORGANIZADOR), eq(9L), captor.capture());
        assertThat(captor.getValue().otros()).containsOnlyKeys("valorInscripcion");
    }

    @Test
    @WithMockUser(authorities = "TORNEO_GESTIONAR")
    @DisplayName("PUT /api/v1/torneos/{id}/informacion con campos bloqueados responde 409 con la lista")
    void camposBloqueados() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));
        when(torneoService.actualizarInformacion(eq(ORGANIZADOR), eq(9L), any(InformacionTorneoRequest.class)))
                .thenThrow(new ConflictoException(CodigoError.CAMPOS_BLOQUEADOS, "bloqueados")
                        .con("campos", List.of("sede", "fechaInicio")));

        mockMvc.perform(put("/api/v1/torneos/9/informacion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre": "Apertura F5", "disciplina": "Fútbol 5", "sede": "Club Luján"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CAMPOS_BLOQUEADOS"))
                .andExpect(jsonPath("$.campos[1]").value("fechaInicio"));
    }

    @Test
    @WithMockUser(authorities = "TORNEO_GESTIONAR")
    @DisplayName("POST /api/v1/torneos sin disciplina responde 400 REQUERIDO sin llamar al servicio")
    void sinDisciplina() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));

        mockMvc.perform(post("/api/v1/torneos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre": "Apertura F5", "disciplina": " "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores[?(@.campo == 'disciplina')].codigo").value("REQUERIDO"));
        verify(torneoService, never()).crear(any(), any());
    }

    @Test
    @WithMockUser(authorities = "TORNEO_GESTIONAR")
    @DisplayName("Un tamaño de equipo de 0 responde 400 VALOR_INVALIDO sin llamar al servicio")
    void tamanioDeEquipoCero() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));

        mockMvc.perform(put("/api/v1/torneos/9/informacion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre": "Apertura F5", "disciplina": "Fútbol 5", "tipoParticipante": "EQUIPO",
                                 "tamanioEquipo": 0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[?(@.campo == 'tamanioEquipo')].codigo").value("VALOR_INVALIDO"));
        verify(torneoService, never()).actualizarInformacion(any(), any(), any());
    }

    @Test
    @WithMockUser(authorities = "TORNEO_GESTIONAR")
    @DisplayName("PUT /api/v1/torneos/{id}/informacion pasa el id y el usuario de la sesión")
    void actualiza() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));
        when(torneoService.actualizarInformacion(eq(ORGANIZADOR), eq(9L), any(InformacionTorneoRequest.class)))
                .thenReturn(borrador());

        mockMvc.perform(put("/api/v1/torneos/9/informacion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre": "Apertura F5", "disciplina": "Fútbol 5", "cupoMaximo": 16, "cupoMinimo": 12}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(9));
    }

    @Test
    @WithMockUser(authorities = "TORNEO_GESTIONAR")
    @DisplayName("PUT /api/v1/torneos/{id}/checkin pasa la ventana y el usuario de la sesión")
    void configuraCheckin() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));
        when(torneoService.configurarCheckin(ORGANIZADOR, 9L, new CheckinTorneoRequest(30, 20)))
                .thenReturn(borrador());

        mockMvc.perform(put("/api/v1/torneos/9/checkin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"minAntes": 30, "duracion": 20}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(9));
    }

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("PUT /api/v1/torneos/{id}/checkin sin TORNEO_GESTIONAR responde 403")
    void configuraCheckinSinPermiso() throws Exception {
        mockMvc.perform(put("/api/v1/torneos/9/checkin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minAntes\": 30, \"duracion\": 20}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(torneoService);
    }

    @Test
    @WithMockUser(authorities = "TORNEO_GESTIONAR")
    @DisplayName("GET /api/v1/torneos/{id} de un torneo ajeno responde 404 TORNEO_NO_ENCONTRADO")
    void ajeno() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));
        when(torneoService.detalle(ORGANIZADOR, 9L))
                .thenThrow(new NoEncontradoException(CodigoError.TORNEO_NO_ENCONTRADO, "No existe el torneo 9"));

        mockMvc.perform(get("/api/v1/torneos/9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("TORNEO_NO_ENCONTRADO"));
    }

    @Test
    @WithMockUser(authorities = "TORNEO_GESTIONAR")
    @DisplayName("GET /api/v1/torneos/opciones pasa la sede para traer sus canchas")
    void opciones() throws Exception {
        when(torneoService.opciones("Club Mercedes")).thenReturn(new OpcionesTorneo(List.of(),
                List.of(new OpcionesTorneo.FormatoOpcion("ELIMINACION_DIRECTA", "Eliminación directa", true)),
                List.of(Modalidad.values()), 64, BigDecimal.TEN, List.of("Cancha 1")));

        mockMvc.perform(get("/api/v1/torneos/opciones").param("sede", "Club Mercedes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cupoMaximo").value(64))
                .andExpect(jsonPath("$.canchas[0]").value("Cancha 1"))
                .andExpect(jsonPath("$.formatos[0].disponible").value(true));
    }

    @Test
    @WithMockUser(authorities = "TORNEO_GESTIONAR")
    @DisplayName("GET /api/v1/torneos/mis-torneos pasa los filtros y la página, y devuelve las cantidades por estado")
    void misTorneos() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));
        MiTorneoResumen fila = new MiTorneoResumen(9L, "Apertura F5", "Fútbol 5", EstadoTorneo.BORRADOR, 2,
                "Club Mercedes", 16, 0, new BigDecimal("56000.00"), true, "ARS", null, CREADO);
        when(misTorneosService.listar(eq(ORGANIZADOR), eq("apertura"), eq(EstadoTorneo.BORRADOR), eq("Fútbol 5"),
                any(Pageable.class)))
                .thenReturn(new MisTorneos(List.of(fila), new PageMetadata(5, 1, 6, 2),
                        Map.of(EstadoTorneo.BORRADOR, 6L), List.of("Fútbol 5")));

        mockMvc.perform(get("/api/v1/torneos/mis-torneos")
                        .param("q", "apertura").param("estado", "BORRADOR").param("disciplina", "Fútbol 5")
                        .param("page", "1").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].pasoPendiente").value(2))
                .andExpect(jsonPath("$.content[0].pozoEstimado").value(true))
                .andExpect(jsonPath("$.page.totalElements").value(6))
                .andExpect(jsonPath("$.porEstado.BORRADOR").value(6))
                .andExpect(jsonPath("$.disciplinas[0]").value("Fútbol 5"));

        ArgumentCaptor<Pageable> pagina = ArgumentCaptor.forClass(Pageable.class);
        verify(misTorneosService).listar(eq(ORGANIZADOR), eq("apertura"), eq(EstadoTorneo.BORRADOR), eq("Fútbol 5"),
                pagina.capture());
        assertThat(pagina.getValue()).isEqualTo(PageRequest.of(1, 5));
    }

    @Test
    @WithMockUser(authorities = "TORNEO_GESTIONAR")
    @DisplayName("POST /api/v1/torneos/{id}/cerrar-inscripcion cierra con el usuario de la sesión y devuelve el torneo")
    void cierraLaInscripcion() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));
        when(inscripcionTorneoService.cerrar(ORGANIZADOR, 9L)).thenReturn(borrador());

        mockMvc.perform(post("/api/v1/torneos/9/cerrar-inscripcion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(9));
        verify(inscripcionTorneoService).cerrar(ORGANIZADOR, 9L);
    }

    @Test
    @WithMockUser(authorities = "TORNEO_GESTIONAR")
    @DisplayName("Cerrar una inscripción que ya no está abierta responde 409 INSCRIPCION_NO_ABIERTA")
    void cerrarYaCerrada() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));
        when(inscripcionTorneoService.cerrar(ORGANIZADOR, 9L))
                .thenThrow(new ConflictoException(CodigoError.INSCRIPCION_NO_ABIERTA, "cerrada"));

        mockMvc.perform(post("/api/v1/torneos/9/cerrar-inscripcion"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("INSCRIPCION_NO_ABIERTA"));
    }

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("Cerrar la inscripción sin TORNEO_GESTIONAR responde 403 sin llamar al servicio")
    void cerrarSinPermiso() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));

        mockMvc.perform(post("/api/v1/torneos/9/cerrar-inscripcion"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(inscripcionTorneoService);
    }

    @Test
    @WithMockUser(authorities = "TORNEO_GESTIONAR")
    @DisplayName("GET /api/v1/torneos/{id}/inscripciones pasa el filtro y la página, y devuelve las filas")
    void inscripcionesDelTorneo() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));
        InscripcionDelTorneo fila = new InscripcionDelTorneo(31L, EstadoInscripcion.CONFIRMADA, CREADO, null,
                new EquipoInscripto(4L, "Los Halcones"), new UsuarioPublico(7L, "cap", "Ana", "Paz"), 5);
        when(inscripcionTorneoService.listar(eq(ORGANIZADOR), eq(9L), eq(EstadoInscripcion.CONFIRMADA), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(fila), PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/api/v1/torneos/9/inscripciones").param("estado", "CONFIRMADA").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(31))
                .andExpect(jsonPath("$.content[0].equipo.nombre").value("Los Halcones"))
                .andExpect(jsonPath("$.content[0].capitan.username").value("cap"))
                .andExpect(jsonPath("$.content[0].jugadores").value(5))
                .andExpect(jsonPath("$.page.totalElements").value(1));

        ArgumentCaptor<Pageable> pagina = ArgumentCaptor.forClass(Pageable.class);
        verify(inscripcionTorneoService).listar(eq(ORGANIZADOR), eq(9L), eq(EstadoInscripcion.CONFIRMADA), pagina.capture());
        assertThat(pagina.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("Las inscripciones del torneo sin TORNEO_GESTIONAR responden 403 sin llamar al servicio")
    void inscripcionesDelTorneoSinPermiso() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));

        mockMvc.perform(get("/api/v1/torneos/9/inscripciones"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(inscripcionTorneoService);
    }

    private static TorneoInformacion borrador() {
        return new TorneoInformacion(9L, EstadoTorneo.BORRADOR, 1, "Apertura F5", "Fútbol 5", null,
                Formato.ELIMINACION_DIRECTA, Modalidad.PRESENCIAL, null, "Club Mercedes", List.of("Cancha 1"), null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                CREADO, CREADO, List.of());
    }
}
