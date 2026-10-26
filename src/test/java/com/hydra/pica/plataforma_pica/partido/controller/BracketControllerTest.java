package com.hydra.pica.plataforma_pica.partido.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.dto.BracketTorneo;
import com.hydra.pica.plataforma_pica.partido.dto.LadoBracket;
import com.hydra.pica.plataforma_pica.partido.dto.PartidoBracket;
import com.hydra.pica.plataforma_pica.partido.dto.RondaBracket;
import com.hydra.pica.plataforma_pica.partido.service.BracketService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * El bracket (PICA-215 y PICA-216): generarlo y regenerarlo es del organizador de la sesión, verlo es público. Las
 * reglas están en el servicio.
 */
@WebMvcTest(BracketController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class BracketControllerTest {

    private static final Long ORGANIZADOR = 5L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BracketService bracketService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("Sin TORNEO_GESTIONAR responde 403 SIN_PERMISO")
    void sinPermiso() throws Exception {
        mockMvc.perform(post("/api/v1/torneos/9/bracket"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
        verifyNoInteractions(bracketService);
    }

    @Test
    @WithMockUser(authorities = "TORNEO_GESTIONAR")
    @DisplayName("POST responde 201 con Location y el bracket, con el BYE marcado")
    void genera() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));
        PartidoBracket bye = new PartidoBracket(1L, 1, 1, EstadoPartido.CONFIRMADO, true,
                new LadoBracket(3L, "Los Galácticos", null, null, true), null, null, null, "Mario Gómez");
        when(bracketService.generar(ORGANIZADOR, 9L)).thenReturn(new BracketTorneo(9L, "ab".repeat(32), null,
                List.of(new RondaBracket(1, "Semifinal", List.of(bye)))));

        mockMvc.perform(post("/api/v1/torneos/9/bracket"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/torneos/9/bracket"))
                .andExpect(jsonPath("$.rondas[0].nombre").value("Semifinal"))
                .andExpect(jsonPath("$.rondas[0].partidos[0].bye").value(true))
                .andExpect(jsonPath("$.rondas[0].partidos[0].local.nombre").value("Los Galácticos"))
                .andExpect(jsonPath("$.rondas[0].partidos[0].visitante").isEmpty());
    }

    @Test
    @DisplayName("GET sin sesión responde 200 con el bracket")
    void publico() throws Exception {
        when(bracketService.ver(9L)).thenReturn(new BracketTorneo(9L, null, null, List.of()));

        mockMvc.perform(get("/api/v1/torneos/9/bracket"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.torneoId").value(9))
                .andExpect(jsonPath("$.rondas").isEmpty());
    }

    @Test
    @WithMockUser(authorities = "TORNEO_GESTIONAR")
    @DisplayName("Regenerar con resultados responde 409 BRACKET_CON_RESULTADOS")
    void regenerarConResultados() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));
        when(bracketService.regenerar(ORGANIZADOR, 9L))
                .thenThrow(new ConflictoException(CodigoError.BRACKET_CON_RESULTADOS, "con resultados"));

        mockMvc.perform(post("/api/v1/torneos/9/bracket/regenerar"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("BRACKET_CON_RESULTADOS"));
    }
}
