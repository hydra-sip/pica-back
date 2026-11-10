package com.hydra.pica.plataforma_pica.torneo.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.torneo.dto.CancelacionRealizada;
import com.hydra.pica.plataforma_pica.torneo.dto.SolicitudCancelacion;
import com.hydra.pica.plataforma_pica.torneo.service.CancelacionTorneoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Cancelar un torneo (HU-605): lo multipart y la respuesta; las reglas están en el servicio. */
@WebMvcTest(CancelacionTorneoController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class CancelacionTorneoControllerTest {

    private static final Long ORGANIZADOR = 5L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CancelacionTorneoService cancelacionTorneoService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("Cancelar sin TORNEO_GESTIONAR responde 403 SIN_PERMISO")
    void sinPermiso() throws Exception {
        mockMvc.perform(multipart("/api/v1/torneos/9/cancelacion").param("motivo", "Lluvia"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
        verifyNoInteractions(cancelacionTorneoService);
    }

    @Test
    @WithMockUser(authorities = "TORNEO_GESTIONAR")
    @DisplayName("Sin gastos pasa el motivo y responde 201 con la cancelación")
    void sinGastos() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));
        when(cancelacionTorneoService.cancelar(eq(ORGANIZADOR), eq(9L), any())).thenReturn(new CancelacionRealizada(
                3L, 9L, "AUTOMATICA", BigDecimal.ZERO, new BigDecimal("3000.00"), new BigDecimal("3300.00")));

        mockMvc.perform(multipart("/api/v1/torneos/9/cancelacion").param("motivo", "Lluvia"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cancelacionId").value(3))
                .andExpect(jsonPath("$.estado").value("AUTOMATICA"))
                .andExpect(jsonPath("$.pozo").value(3000.00))
                .andExpect(jsonPath("$.reintegrado").value(3300.00));

        verify(cancelacionTorneoService).cancelar(ORGANIZADOR, 9L, new SolicitudCancelacion("Lluvia", List.of()));
    }
}
