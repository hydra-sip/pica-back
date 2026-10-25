package com.hydra.pica.plataforma_pica.inscripcion.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionDetalle;
import com.hydra.pica.plataforma_pica.inscripcion.service.InscripcionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Inscripción individual (PICA-208): el usuario sale de la sesión y las reglas están en InscripcionService. */
@WebMvcTest(InscripcionController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class InscripcionControllerTest {

    private static final Long PARTICIPANTE = 5L;
    private static final Instant CREADA = Instant.parse("2026-10-24T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InscripcionService inscripcionService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @DisplayName("Sin autenticación responde 401 en los tres endpoints")
    void sinAutenticacion() throws Exception {
        mockMvc.perform(post("/api/v1/torneos/3/inscripciones")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/inscripciones/7")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/inscripciones/7")).andExpect(status().isUnauthorized());
        verifyNoInteractions(inscripcionService);
    }

    @Test
    @WithMockUser(authorities = "ROL_VER")
    @DisplayName("Sin INSCRIPCION_GESTIONAR, crear y cancelar responden 403 SIN_PERMISO")
    void sinPermiso() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));

        mockMvc.perform(post("/api/v1/torneos/3/inscripciones"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
        mockMvc.perform(delete("/api/v1/inscripciones/7"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
        verifyNoInteractions(inscripcionService);
    }

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("POST /torneos/{id}/inscripciones crea con el usuario de la sesión y responde 201 con Location")
    void crea() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));
        when(inscripcionService.crear(PARTICIPANTE, 3L)).thenReturn(detalle());

        mockMvc.perform(post("/api/v1/torneos/3/inscripciones"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/inscripciones/7"))
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.torneoId").value(3))
                .andExpect(jsonPath("$.estado").value("BORRADOR"));
    }

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("Una regla incumplida llega como 409 con su código")
    void reglaIncumplida() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));
        when(inscripcionService.crear(PARTICIPANTE, 3L))
                .thenThrow(new ConflictoException(CodigoError.INSCRIPCION_NO_ABIERTA, "cerrado"));

        mockMvc.perform(post("/api/v1/torneos/3/inscripciones"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("INSCRIPCION_NO_ABIERTA"));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /inscripciones/{id} alcanza con estar logueado: lo ve también el organizador")
    void ve() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));
        when(inscripcionService.detalle(PARTICIPANTE, 7L)).thenReturn(detalle());

        mockMvc.perform(get("/api/v1/inscripciones/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.usuarioId").value(PARTICIPANTE));
    }

    @Test
    @WithMockUser
    @DisplayName("GET de una inscripción ajena responde 404 INSCRIPCION_NO_ENCONTRADA")
    void ajena() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));
        when(inscripcionService.detalle(PARTICIPANTE, 7L))
                .thenThrow(new NoEncontradoException(CodigoError.INSCRIPCION_NO_ENCONTRADA, "no existe"));

        mockMvc.perform(get("/api/v1/inscripciones/7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("INSCRIPCION_NO_ENCONTRADA"));
    }

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("DELETE /inscripciones/{id} cancela con el usuario de la sesión y responde 204")
    void cancela() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));

        mockMvc.perform(delete("/api/v1/inscripciones/7")).andExpect(status().isNoContent());

        verify(inscripcionService).cancelar(PARTICIPANTE, 7L);
    }

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("DELETE de una inscripción en otro estado responde 409 INSCRIPCION_NO_EDITABLE")
    void noSeCancela() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));
        doThrow(new ConflictoException(CodigoError.INSCRIPCION_NO_EDITABLE, "confirmada"))
                .when(inscripcionService).cancelar(PARTICIPANTE, 7L);

        mockMvc.perform(delete("/api/v1/inscripciones/7"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("INSCRIPCION_NO_EDITABLE"));
    }

    private InscripcionDetalle detalle() {
        return new InscripcionDetalle(7L, 3L, PARTICIPANTE, EstadoInscripcion.BORRADOR, null, CREADA, CREADA);
    }
}
