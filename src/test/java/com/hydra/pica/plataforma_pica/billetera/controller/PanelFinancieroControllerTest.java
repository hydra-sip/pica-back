package com.hydra.pica.plataforma_pica.billetera.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.billetera.dto.PanelFinanciero;
import com.hydra.pica.plataforma_pica.billetera.dto.RepartoLiquidacion;
import com.hydra.pica.plataforma_pica.billetera.service.FondosEnCustodiaException;
import com.hydra.pica.plataforma_pica.billetera.service.PanelFinancieroService;
import com.hydra.pica.plataforma_pica.billetera.service.PanelFinancieroService.LiquidacionFinal;
import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PanelFinancieroController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class PanelFinancieroControllerTest {

    private static final Long USUARIO_ID = 42L;
    private static final Instant LIQUIDADO = Instant.parse("2026-11-10T15:00:00Z");

    @Autowired private MockMvc mockMvc;

    @MockitoBean private PanelFinancieroService panelFinancieroService;
    @MockitoBean private CurrentUserProvider currentUserProvider;

    @BeforeEach
    void sesion() {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(USUARIO_ID));
    }

    @Test
    @DisplayName("Sin sesión responde 401")
    void sinSesion() throws Exception {
        mockMvc.perform(get("/api/v1/torneos/7/panel-financiero")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/torneos/7/liquidacion").param("formato", "csv"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(panelFinancieroService);
    }

    @Test
    @WithMockUser
    @DisplayName("GET /torneos/{id}/panel-financiero responde los indicadores")
    void panel() throws Exception {
        when(panelFinancieroService.delTorneo(USUARIO_ID, 7L)).thenReturn(new PanelFinanciero(7L, "Copa",
                EstadoTorneo.EN_CURSO, false, null, "ARS", new BigDecimal("4000.00"), new BigDecimal("400.00"),
                BigDecimal.ZERO, new BigDecimal("500.00"), new BigDecimal("500.00"), new BigDecimal("3500.00"), 4));

        mockMvc.perform(get("/api/v1/torneos/7/panel-financiero"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recaudado").value(4000.00))
                .andExpect(jsonPath("$.reintegroOrganizador").value(500.00))
                .andExpect(jsonPath("$.comprometidoEnPremios").value(3500.00))
                .andExpect(jsonPath("$.liquidado").value(false));
    }

    @Test
    @WithMockUser
    @DisplayName("Con formato=csv o pdf baja el archivo como adjunto")
    void descargar() throws Exception {
        when(panelFinancieroService.liquidacionFinal(USUARIO_ID, 7L)).thenReturn(new LiquidacionFinal(7L, "Copa",
                new RepartoLiquidacion(true, LIQUIDADO, new BigDecimal("100.00"), "ARS", List.of())));

        mockMvc.perform(get("/api/v1/torneos/7/liquidacion").param("formato", "csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"liquidacion-torneo-7.csv\""));
        mockMvc.perform(get("/api/v1/torneos/7/liquidacion").param("formato", "PDF"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"liquidacion-torneo-7.pdf\""));
    }

    @Test
    @WithMockUser
    @DisplayName("Un formato que no es csv ni pdf es 400, y sin liquidar es 409 FONDOS_EN_CUSTODIA")
    void errores() throws Exception {
        mockMvc.perform(get("/api/v1/torneos/7/liquidacion").param("formato", "xlsx"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"));

        when(panelFinancieroService.liquidacionFinal(any(), any()))
                .thenThrow(new FondosEnCustodiaException("El reporte de liquidación final solo se emite después"));
        mockMvc.perform(get("/api/v1/torneos/7/liquidacion").param("formato", "pdf"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("FONDOS_EN_CUSTODIA"));
    }
}
