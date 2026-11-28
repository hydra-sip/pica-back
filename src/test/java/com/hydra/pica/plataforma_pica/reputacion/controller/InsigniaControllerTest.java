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
import com.hydra.pica.plataforma_pica.reputacion.dto.InsigniaObtenida;
import com.hydra.pica.plataforma_pica.reputacion.dto.LogrosJugador;
import com.hydra.pica.plataforma_pica.reputacion.dto.RifaGanada;
import com.hydra.pica.plataforma_pica.reputacion.service.InsigniaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InsigniaController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class InsigniaControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private InsigniaService insigniaService;

    @Test
    @DisplayName("Sin sesión responde 401")
    void sinSesion() throws Exception {
        mockMvc.perform(get("/api/v1/usuarios/3/logros")).andExpect(status().isUnauthorized());
        verifyNoInteractions(insigniaService);
    }

    @Test
    @WithMockUser
    @DisplayName("Las insignias y rifas ganadas responden 200")
    void ver() throws Exception {
        Instant cuando = Instant.parse("2026-11-20T18:00:00Z");
        when(insigniaService.ver(3L)).thenReturn(new LogrosJugador(
                3L,
                List.of(new InsigniaObtenida(
                        "PRIMERA_RIFA_GANADA",
                        "Suerte de campeón",
                        "Ganaste un premio en una rifa sorteada",
                        "ticket-estrella",
                        cuando)),
                List.of(new RifaGanada(
                        12L,
                        "Rifa Apertura",
                        7L,
                        "Copa Apertura",
                        "copa-apertura",
                        1,
                        14,
                        cuando))));

        mockMvc.perform(get("/api/v1/usuarios/3/logros"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuarioId").value(3))
                .andExpect(jsonPath("$.insignias[0].codigo").value("PRIMERA_RIFA_GANADA"))
                .andExpect(jsonPath("$.insignias[0].nombre").value("Suerte de campeón"))
                .andExpect(jsonPath("$.insignias[0].icono").value("ticket-estrella"))
                .andExpect(jsonPath("$.rifasGanadas[0].rifaTitulo").value("Rifa Apertura"))
                .andExpect(jsonPath("$.rifasGanadas[0].torneoNombre").value("Copa Apertura"))
                .andExpect(jsonPath("$.rifasGanadas[0].numeroGanador").value(14));
    }

    @Test
    @WithMockUser
    @DisplayName("El de un usuario que no existe responde 404")
    void noExiste() throws Exception {
        when(insigniaService.ver(99L)).thenThrow(new NoEncontradoException(CodigoError.USUARIO_NO_ENCONTRADO, "no"));

        mockMvc.perform(get("/api/v1/usuarios/99/logros"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("USUARIO_NO_ENCONTRADO"));
    }
}
