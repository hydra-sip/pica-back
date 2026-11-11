package com.hydra.pica.plataforma_pica.torneo.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoDesignacion;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.PermisoArbitro;
import com.hydra.pica.plataforma_pica.torneo.dto.MiDesignacion;
import com.hydra.pica.plataforma_pica.torneo.service.DesignacionesService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DesignacionController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class DesignacionControllerTest {

    private static final Long ARBITRO = 5L;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private DesignacionesService designacionesService;
    @MockitoBean private CurrentUserProvider currentUserProvider;

    private static MiDesignacion designacion(EstadoDesignacion estado) {
        return new MiDesignacion(9L, "Apertura F5", "apertura-f5", "Fútbol 5", EstadoTorneo.INSCRIPCION_ABIERTA,
                Instant.parse("2026-11-20T18:00:00Z"), null, "Olga Organizadora", estado,
                Set.of(PermisoArbitro.RESOLVER_OBJECIONES), Instant.parse("2026-11-04T12:00:00Z"), null, null, null);
    }

    @Test
    @DisplayName("Sin sesión responde 401")
    void sinSesion() throws Exception {
        mockMvc.perform(get("/api/v1/designaciones/mias")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/designaciones/9/aceptar")).andExpect(status().isUnauthorized());
        verifyNoInteractions(designacionesService);
    }

    @Test
    @WithMockUser
    @DisplayName("GET /designaciones/mias responde las del que llama, sin pedir permisos")
    void mias() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ARBITRO));
        when(designacionesService.mias(ARBITRO)).thenReturn(List.of(designacion(EstadoDesignacion.PENDIENTE)));

        mockMvc.perform(get("/api/v1/designaciones/mias"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].torneoId").value(9))
                .andExpect(jsonPath("$[0].torneo").value("Apertura F5"))
                .andExpect(jsonPath("$[0].organizador").value("Olga Organizadora"))
                .andExpect(jsonPath("$[0].estado").value("PENDIENTE"))
                .andExpect(jsonPath("$[0].permisos[0]").value("RESOLVER_OBJECIONES"));
    }

    @Test
    @WithMockUser
    @DisplayName("Aceptar responde 200 con la designación aceptada")
    void aceptar() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ARBITRO));
        when(designacionesService.aceptar(ARBITRO, 9L, true)).thenReturn(designacion(EstadoDesignacion.ACEPTADO));

        mockMvc.perform(post("/api/v1/designaciones/9/aceptar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"compromisoAceptado\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ACEPTADO"));
    }

    @Test
    @WithMockUser
    @DisplayName("Aceptar sin cuerpo cuenta como sin el código de imparcialidad")
    void aceptarSinCompromiso() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ARBITRO));
        when(designacionesService.aceptar(ARBITRO, 9L, false)).thenThrow(
                new ConflictoException(CodigoError.COMPROMISO_NO_ACEPTADO, "sin compromiso"));

        mockMvc.perform(post("/api/v1/designaciones/9/aceptar"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("COMPROMISO_NO_ACEPTADO"));
    }

    @Test
    @WithMockUser
    @DisplayName("Rechazar pasa el motivo, y uno de más de 500 caracteres es 400")
    void rechazarConMotivo() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ARBITRO));
        when(designacionesService.rechazar(ARBITRO, 9L, "Viajo ese fin de semana"))
                .thenReturn(designacion(EstadoDesignacion.RECHAZADO));

        mockMvc.perform(post("/api/v1/designaciones/9/rechazar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\": \"Viajo ese fin de semana\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RECHAZADO"));

        mockMvc.perform(post("/api/v1/designaciones/9/rechazar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\": \"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    @DisplayName("Rechazar una ya respondida es 409 DESIGNACION_YA_RESPONDIDA con el estado")
    void yaRespondida() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ARBITRO));
        when(designacionesService.rechazar(ARBITRO, 9L, null)).thenThrow(
                new ConflictoException(CodigoError.DESIGNACION_YA_RESPONDIDA, "ya está ACEPTADO")
                        .con("estado", EstadoDesignacion.ACEPTADO));

        mockMvc.perform(post("/api/v1/designaciones/9/rechazar"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("DESIGNACION_YA_RESPONDIDA"))
                .andExpect(jsonPath("$.estado").value("ACEPTADO"));
    }

    @Test
    @WithMockUser
    @DisplayName("Sin designación responde 404 DESIGNACION_NO_ENCONTRADA")
    void noDesignado() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ARBITRO));
        when(designacionesService.aceptar(ARBITRO, 9L, false)).thenThrow(
                new NoEncontradoException(CodigoError.DESIGNACION_NO_ENCONTRADA, "no designado"));

        mockMvc.perform(post("/api/v1/designaciones/9/aceptar"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("DESIGNACION_NO_ENCONTRADA"));
    }
}
