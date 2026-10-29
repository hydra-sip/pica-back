package com.hydra.pica.plataforma_pica.rifa.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.error.ProhibidoException;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoReclamo;
import com.hydra.pica.plataforma_pica.rifa.dto.ReclamoRifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.service.ReclamoRifaService;
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

@WebMvcTest(ReclamoRifaController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class ReclamoRifaControllerTest {

    private static final Long USUARIO_ID = 42L;
    private static final Long RIFA_ID = 10L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReclamoRifaService reclamoRifaService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @DisplayName("POST /api/v1/rifas/{id}/reclamos sin autenticación responde 401")
    void sinAutenticacionResponde401() throws Exception {
        mockMvc.perform(post("/api/v1/rifas/10/reclamos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"Error en el sorteo\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(reclamoRifaService);
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/v1/rifas/{id}/reclamos con comprador y dentro de 48h responde 201 Created")
    void registrarReclamoExitoso() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(USUARIO_ID));
        ReclamoRifaDetalle detalle = new ReclamoRifaDetalle(
                100L,
                RIFA_ID,
                USUARIO_ID,
                "Se detectó una irregularidad en el cálculo de la semilla",
                EstadoReclamo.PENDIENTE,
                null,
                null,
                null,
                Instant.parse("2026-10-28T12:00:00Z")
        );
        when(reclamoRifaService.registrarReclamo(eq(USUARIO_ID), eq(RIFA_ID), any())).thenReturn(detalle);

        mockMvc.perform(post("/api/v1/rifas/10/reclamos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"Se detectó una irregularidad en el cálculo de la semilla\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/rifas/10/reclamos/100"))
                .andExpect(jsonPath("$.id").value(100))
                .andExpect(jsonPath("$.rifaId").value(10))
                .andExpect(jsonPath("$.usuarioId").value(42))
                .andExpect(jsonPath("$.motivo").value("Se detectó una irregularidad en el cálculo de la semilla"))
                .andExpect(jsonPath("$.estado").value("PENDIENTE"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/v1/rifas/{id}/reclamos sin haber comprado ticket responde 403 NO_ES_COMPRADOR")
    void registrarReclamoNoEsComprador() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(USUARIO_ID));
        when(reclamoRifaService.registrarReclamo(eq(USUARIO_ID), eq(RIFA_ID), any()))
                .thenThrow(new ProhibidoException(CodigoError.NO_ES_COMPRADOR,
                        "Solo los compradores de tickets pueden reclamar el resultado"));

        mockMvc.perform(post("/api/v1/rifas/10/reclamos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"Sospecho de irregularidad\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("NO_ES_COMPRADOR"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/v1/rifas/{id}/reclamos fuera del plazo de 48h responde 409 PLAZO_RECLAMO_VENCIDO")
    void registrarReclamoPlazoVencido() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(USUARIO_ID));
        when(reclamoRifaService.registrarReclamo(eq(USUARIO_ID), eq(RIFA_ID), any()))
                .thenThrow(new ConflictoException(CodigoError.PLAZO_RECLAMO_VENCIDO,
                        "El plazo de 48 horas posteriores a la finalización de la rifa ha expirado"));

        mockMvc.perform(post("/api/v1/rifas/10/reclamos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"Reclamo tardío\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("PLAZO_RECLAMO_VENCIDO"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/v1/rifas/{id}/reclamos en rifa no sorteada responde 409 RIFA_NO_SORTEADA")
    void registrarReclamoRifaNoSorteada() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(USUARIO_ID));
        when(reclamoRifaService.registrarReclamo(eq(USUARIO_ID), eq(RIFA_ID), any()))
                .thenThrow(new ConflictoException(CodigoError.RIFA_NO_SORTEADA,
                        "No se puede reclamar una rifa que no ha finalizado su sorteo"));

        mockMvc.perform(post("/api/v1/rifas/10/reclamos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"Aún activa\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("RIFA_NO_SORTEADA"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/v1/rifas/{id}/reclamos con motivo vacío responde 400 VALIDACION")
    void registrarReclamoMotivoVacioResponde400() throws Exception {
        mockMvc.perform(post("/api/v1/rifas/10/reclamos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"));

        verifyNoInteractions(reclamoRifaService);
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/v1/rifas/{id}/reclamos retorna 200 con la lista de reclamos")
    void listarReclamosRetorna200() throws Exception {
        ReclamoRifaDetalle r1 = new ReclamoRifaDetalle(
                1L, RIFA_ID, USUARIO_ID, "Motivo 1", EstadoReclamo.PENDIENTE, null, null, null, Instant.now()
        );
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(USUARIO_ID));
        when(reclamoRifaService.listarPorRifa(USUARIO_ID, RIFA_ID)).thenReturn(List.of(r1));

        mockMvc.perform(get("/api/v1/rifas/10/reclamos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].motivo").value("Motivo 1"))
                .andExpect(jsonPath("$[0].estado").value("PENDIENTE"));
    }
}
