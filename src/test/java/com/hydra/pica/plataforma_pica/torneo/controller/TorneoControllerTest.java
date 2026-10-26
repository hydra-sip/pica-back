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
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Formato;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.dto.InformacionTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.MiTorneoResumen;
import com.hydra.pica.plataforma_pica.torneo.dto.MisTorneos;
import com.hydra.pica.plataforma_pica.torneo.dto.OpcionesTorneo;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoInformacion;
import com.hydra.pica.plataforma_pica.torneo.service.MisTorneosService;
import com.hydra.pica.plataforma_pica.torneo.service.TorneoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
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

    private static TorneoInformacion borrador() {
        return new TorneoInformacion(9L, EstadoTorneo.BORRADOR, 1, "Apertura F5", "Fútbol 5", null,
                Formato.ELIMINACION_DIRECTA, Modalidad.PRESENCIAL, null, "Club Mercedes", List.of("Cancha 1"), null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, CREADO, CREADO);
    }
}
