package com.hydra.pica.plataforma_pica.reputacion.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.reputacion.domain.Gravedad;
import com.hydra.pica.plataforma_pica.reputacion.domain.TipoEventoFairPlay;
import com.hydra.pica.plataforma_pica.reputacion.dto.FairPlayDetalle;
import com.hydra.pica.plataforma_pica.reputacion.service.FairPlayService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FairPlayController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class FairPlayControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private FairPlayService fairPlayService;

    @Test
    @DisplayName("Sin sesión responde 401")
    void sinSesion() throws Exception {
        mockMvc.perform(get("/api/v1/usuarios/3/fair-play")).andExpect(status().isUnauthorized());
        verifyNoInteractions(fairPlayService);
    }

    @Test
    @WithMockUser
    @DisplayName("El índice con su historial responde 200")
    void ver() throws Exception {
        Instant cuando = Instant.parse("2026-11-15T18:00:00Z");
        when(fairPlayService.ver(3L)).thenReturn(new FairPlayDetalle(3L, 85, cuando, List.of(
                new FairPlayDetalle.Movimiento(TipoEventoFairPlay.SANCION, -15, 85, 9L, "Copa", null, 7L,
                        Gravedad.MEDIA, cuando))));

        mockMvc.perform(get("/api/v1/usuarios/3/fair-play"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valor").value(85))
                .andExpect(jsonPath("$.historial[0].tipo").value("SANCION"))
                .andExpect(jsonPath("$.historial[0].delta").value(-15))
                .andExpect(jsonPath("$.historial[0].gravedad").value("MEDIA"))
                .andExpect(jsonPath("$.historial[0].partidoId").isEmpty());
    }

    @Test
    @WithMockUser
    @DisplayName("El de un usuario que no existe responde 404")
    void noExiste() throws Exception {
        when(fairPlayService.ver(99L)).thenThrow(new NoEncontradoException(CodigoError.USUARIO_NO_ENCONTRADO, "no"));

        mockMvc.perform(get("/api/v1/usuarios/99/fair-play"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("USUARIO_NO_ENCONTRADO"));
    }
}
