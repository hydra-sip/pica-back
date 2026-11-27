package com.hydra.pica.plataforma_pica.torneo.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.torneo.dto.RechazoReprogramacion;
import com.hydra.pica.plataforma_pica.torneo.service.RechazoReprogramacionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Rechazar la nueva fecha (CR-01-3): el permiso y la respuesta; las reglas están en el servicio. */
@WebMvcTest(RechazoReprogramacionController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class RechazoReprogramacionControllerTest {

    private static final Long JUGADOR = 8L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RechazoReprogramacionService rechazoReprogramacionService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @WithMockUser(authorities = "TORNEO_GESTIONAR")
    @DisplayName("Sin INSCRIPCION_GESTIONAR responde 403 SIN_PERMISO")
    void sinPermiso() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(JUGADOR));
        mockMvc.perform(post("/api/v1/inscripciones/4/rechazo-reprogramacion"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
        verifyNoInteractions(rechazoReprogramacionService);
    }

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("Rechaza con el usuario de la sesión y responde 200 con lo reintegrado")
    void rechaza() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(JUGADOR));
        when(rechazoReprogramacionService.rechazar(JUGADOR, 4L))
                .thenReturn(new RechazoReprogramacion(4L, 9L, "REINTEGRADA", new BigDecimal("1000.00")));

        mockMvc.perform(post("/api/v1/inscripciones/4/rechazo-reprogramacion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inscripcionId").value(4))
                .andExpect(jsonPath("$.estado").value("REINTEGRADA"))
                .andExpect(jsonPath("$.reintegrado").value(1000.00));
    }
}
