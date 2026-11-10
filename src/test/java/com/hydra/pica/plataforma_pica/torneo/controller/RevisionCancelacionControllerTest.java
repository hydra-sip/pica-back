package com.hydra.pica.plataforma_pica.torneo.controller;

import static org.mockito.Mockito.verify;
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
import com.hydra.pica.plataforma_pica.torneo.dto.ResolucionCancelacion;
import com.hydra.pica.plataforma_pica.torneo.service.RevisionCancelacionService;
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

/** La revisión de soporte de las cancelaciones (HU-605, PICA-348): lo HTTP; las reglas están en el servicio. */
@WebMvcTest(RevisionCancelacionController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class RevisionCancelacionControllerTest {

    private static final Long SOPORTE = 7L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RevisionCancelacionService revisionCancelacionService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @WithMockUser(authorities = "TORNEO_GESTIONAR")
    @DisplayName("Sin CANCELACION_REVISAR responde 403 SIN_PERMISO")
    void sinPermiso() throws Exception {
        mockMvc.perform(post("/api/v1/admin/cancelaciones/9/rechazar")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\": \"No\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
        verifyNoInteractions(revisionCancelacionService);
    }

    @Test
    @WithMockUser(authorities = "CANCELACION_REVISAR")
    @DisplayName("Rechazar pasa el motivo y responde con la resolución")
    void rechazar() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(SOPORTE));
        when(revisionCancelacionService.rechazar(SOPORTE, 9L, "No son del torneo")).thenReturn(
                new ResolucionCancelacion(9L, "RECHAZADA", new BigDecimal("3300.00"), BigDecimal.ZERO,
                        BigDecimal.ZERO));

        mockMvc.perform(post("/api/v1/admin/cancelaciones/9/rechazar")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\": \"No son del torneo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RECHAZADA"))
                .andExpect(jsonPath("$.reintegrado").value(3300.00));

        verify(revisionCancelacionService).rechazar(SOPORTE, 9L, "No son del torneo");
    }
}
