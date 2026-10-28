package com.hydra.pica.plataforma_pica.rifa.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.rifa.dto.VentaRifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.VentaRifaResultado;
import com.hydra.pica.plataforma_pica.rifa.service.VentaRifaService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VentaRifaController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class VentaRifaControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private VentaRifaService ventaRifaService;
    @MockitoBean private CurrentUserProvider currentUserProvider;

    @Test
    @WithMockUser
    @DisplayName("Comprar números autenticado responde 200 con el resultado")
    void comprarAutenticadoResponde200() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(10L));
        when(ventaRifaService.vender(eq(10L), eq(1L), any(), any()))
                .thenReturn(new VentaRifaResultado(
                        100L,
                        "T-ABCD1234",
                        List.of(7, 8),
                        new BigDecimal("500.00"),
                        new BigDecimal("2500.00"),
                        new BigDecimal("60000.00")
                ));

        VentaRifaRequest request = new VentaRifaRequest(List.of(7, 8), "clave-idemp");

        mockMvc.perform(post("/api/v1/rifas/1/comprar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketId").value(100))
                .andExpect(jsonPath("$.ticketCodigo").value("T-ABCD1234"))
                .andExpect(jsonPath("$.montoTotal").value(500.00))
                .andExpect(jsonPath("$.pozoRifa").value(2500.00))
                .andExpect(jsonPath("$.pozoTorneo").value(60000.00));
    }

    @Test
    @DisplayName("Comprar números sin autenticación responde 401 NO_AUTENTICADO")
    void comprarSinSesionResponde401() throws Exception {
        VentaRifaRequest request = new VentaRifaRequest(List.of(7), null);

        mockMvc.perform(post("/api/v1/rifas/1/comprar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }
}
