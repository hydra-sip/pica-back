package com.hydra.pica.plataforma_pica.partido.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.dto.MiPronostico;
import com.hydra.pica.plataforma_pica.partido.dto.PartidoPronostico;
import com.hydra.pica.plataforma_pica.partido.dto.PronosticoDetalle;
import com.hydra.pica.plataforma_pica.partido.service.PronosticoService;
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

@WebMvcTest(PronosticoController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class PronosticoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PronosticoService pronosticoService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @DisplayName("GET /api/v1/pronosticos/partidos es público y retorna 200 con la lista de partidos")
    void listarPartidosPublico() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.empty());
        when(pronosticoService.listarPartidos(null, null, null)).thenReturn(List.of(
                new PartidoPronostico(
                        10L, 1L, "Copa PICA", 1, "Final", 1,
                        EstadoPartido.PROGRAMADO, Instant.parse("2030-01-01T18:00:00Z"),
                        Instant.parse("2030-01-01T18:00:00Z"), true, null, null, null)
        ));

        mockMvc.perform(get("/api/v1/pronosticos/partidos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].partidoId").value(10))
                .andExpect(jsonPath("$[0].torneoNombre").value("Copa PICA"))
                .andExpect(jsonPath("$[0].abierto").value(true));
    }

    @Test
    @WithMockUser
    @DisplayName("PUT /api/v1/partidos/{id}/pronostico registra el pronóstico con 200 si la ventana está abierta")
    void registrarPronosticoExitoso() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(42L));
        when(pronosticoService.pronosticar(eq(42L), eq(10L), any()))
                .thenReturn(new PronosticoDetalle(10L, 42L, "1", null, Instant.now(), Instant.now()));

        mockMvc.perform(put("/api/v1/partidos/10/pronostico")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eleccion\":\"1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partidoId").value(10))
                .andExpect(jsonPath("$.usuarioId").value(42))
                .andExpect(jsonPath("$.eleccion").value("1"));
    }

    @Test
    @WithMockUser
    @DisplayName("PUT /api/v1/partidos/{id}/pronostico responde 409 PRONOSTICO_FUERA_DE_VENTANA si la ventana cerró")
    void registrarPronosticoVentanaCerrada() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(42L));
        when(pronosticoService.pronosticar(eq(42L), eq(10L), any()))
                .thenThrow(new ConflictoException(CodigoError.PRONOSTICO_FUERA_DE_VENTANA,
                        "La ventana de pronósticos ya cerró"));

        mockMvc.perform(put("/api/v1/partidos/10/pronostico")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eleccion\":\"X\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("PRONOSTICO_FUERA_DE_VENTANA"));
    }

    @Test
    @DisplayName("PUT /api/v1/partidos/{id}/pronostico sin autenticación responde 401")
    void registrarPronosticoSinAutenticacion() throws Exception {
        mockMvc.perform(put("/api/v1/partidos/10/pronostico")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eleccion\":\"1\"}"))
                .andExpect(status().isUnauthorized());
    }
}
