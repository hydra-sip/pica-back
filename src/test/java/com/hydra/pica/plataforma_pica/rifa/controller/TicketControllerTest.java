package com.hydra.pica.plataforma_pica.rifa.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.dto.TicketDetalle;
import com.hydra.pica.plataforma_pica.rifa.service.TicketService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TicketController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class TicketControllerTest {

    private static final TicketDetalle TICKET = new TicketDetalle(5L, "R1-4F2K9Q", 1L, "Rifa del club", null, null,
            10L, "Ana García", "agarcia", List.of(7, 8), new BigDecimal("1000.00"), new BigDecimal("100.00"), "ARS",
            Instant.parse("2026-10-29T12:00:00Z"), EstadoRifa.EN_VENTA, null, true, false);

    @Autowired private MockMvc mockMvc;

    @MockitoBean private TicketService ticketService;
    @MockitoBean private CurrentUserProvider currentUserProvider;

    @Test
    @WithMockUser
    @DisplayName("GET /api/v1/tickets/mis-tickets lista los del usuario de la sesión")
    void misTickets() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(10L));
        when(ticketService.misTickets(10L)).thenReturn(List.of(TICKET));

        mockMvc.perform(get("/api/v1/tickets/mis-tickets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].codigo").value("R1-4F2K9Q"))
                .andExpect(jsonPath("$[0].numeros[1]").value(8))
                .andExpect(jsonPath("$[0].comision").value(100.00));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/v1/tickets/{id} devuelve el ticket")
    void detalle() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(10L));
        when(ticketService.detalle(10L, 5L)).thenReturn(TICKET);

        mockMvc.perform(get("/api/v1/tickets/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rifaTitulo").value("Rifa del club"))
                .andExpect(jsonPath("$.esComprador").value(true));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/v1/tickets/{id} de otro o que no existe responde 404 TICKET_NO_ENCONTRADO")
    void noEncontrado() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(10L));
        when(ticketService.detalle(10L, 6L))
                .thenThrow(new NoEncontradoException(CodigoError.TICKET_NO_ENCONTRADO, "No existe el ticket 6"));

        mockMvc.perform(get("/api/v1/tickets/6"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("TICKET_NO_ENCONTRADO"));
    }

    @Test
    @DisplayName("Sin sesión responde 401")
    void sinSesion() throws Exception {
        mockMvc.perform(get("/api/v1/tickets/mis-tickets")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/tickets/5")).andExpect(status().isUnauthorized());
    }
}
