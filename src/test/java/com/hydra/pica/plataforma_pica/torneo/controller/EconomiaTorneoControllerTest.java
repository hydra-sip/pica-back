package com.hydra.pica.plataforma_pica.torneo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.torneo.dto.EconomiaTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.GastoDetalle;
import com.hydra.pica.plataforma_pica.torneo.dto.GastoRequest;
import com.hydra.pica.plataforma_pica.torneo.service.EconomiaTorneoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Paso 3 del asistente (PICA-203): el organizador sale de la sesión y las reglas están en EconomiaTorneoService. */
@WebMvcTest(EconomiaTorneoController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class EconomiaTorneoControllerTest {

    private static final Long ORGANIZADOR = 5L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EconomiaTorneoService economiaService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("Sin TORNEO_GESTIONAR responde 403 SIN_PERMISO")
    void sinPermiso() throws Exception {
        mockMvc.perform(put("/api/v1/torneos/9/economia")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ECONOMIA))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
        verifyNoInteractions(economiaService);
    }

    @Test
    @WithMockUser(authorities = "TORNEO_GESTIONAR")
    @DisplayName("PUT /economia pasa el esquema con los premios en orden")
    void actualizaLaEconomia() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));

        mockMvc.perform(put("/api/v1/torneos/9/economia")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ECONOMIA))
                .andExpect(status().isOk());

        ArgumentCaptor<EconomiaTorneoRequest> captor = ArgumentCaptor.forClass(EconomiaTorneoRequest.class);
        verify(economiaService).actualizarEconomia(eq(ORGANIZADOR), eq(9L), captor.capture());
        assertThat(captor.getValue().premios()).containsExactly(new BigDecimal("60"), new BigDecimal("40"));
    }

    @Test
    @WithMockUser(authorities = "TORNEO_GESTIONAR")
    @DisplayName("Sin valor de inscripción o con 0 responde 400 sin llamar al servicio")
    void valorDeInscripcion() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));

        mockMvc.perform(put("/api/v1/torneos/9/economia")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pctPozoCompetidores": 90, "pctPremioRifa": 0, "pctComision": 10, "premios": []}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[?(@.campo == 'valorInscripcion')].codigo").value("REQUERIDO"));
        mockMvc.perform(put("/api/v1/torneos/9/economia")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"valorInscripcion": 0, "pctPozoCompetidores": 90, "pctPremioRifa": 0,
                                 "pctComision": 10, "premios": []}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[?(@.campo == 'valorInscripcion')].codigo").value("VALOR_INVALIDO"));
        verify(economiaService, never()).actualizarEconomia(any(), any(), any());
    }

    @Test
    @WithMockUser(authorities = "TORNEO_GESTIONAR")
    @DisplayName("POST /gastos responde 201 con Location y DELETE responde 204")
    void gastos() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));
        when(economiaService.agregarGasto(eq(ORGANIZADOR), eq(9L), any(GastoRequest.class)))
                .thenReturn(new GastoDetalle(3, "Alquiler", new BigDecimal("30000")));

        mockMvc.perform(post("/api/v1/torneos/9/gastos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"descripcion": "Alquiler", "monto": 30000}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/torneos/9/gastos/3"))
                .andExpect(jsonPath("$.gastoId").value(3));

        mockMvc.perform(delete("/api/v1/torneos/9/gastos/3"))
                .andExpect(status().isNoContent());
        verify(economiaService).eliminarGasto(ORGANIZADOR, 9L, 3);
    }

    @Test
    @WithMockUser(authorities = "TORNEO_GESTIONAR")
    @DisplayName("Un gasto sin descripción responde 400 REQUERIDO")
    void gastoSinDescripcion() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));

        mockMvc.perform(post("/api/v1/torneos/9/gastos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"descripcion": " ", "monto": 100}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[?(@.campo == 'descripcion')].codigo").value("REQUERIDO"));
        verify(economiaService, never()).agregarGasto(any(), any(), any());
    }

    private static final String ECONOMIA = """
            {"valorInscripcion": 20000, "pctPozoCompetidores": 90, "pctPremioRifa": 0, "pctComision": 10,
             "premios": [60, 40]}
            """;
}
