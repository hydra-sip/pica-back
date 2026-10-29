package com.hydra.pica.plataforma_pica.rifa.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.rifa.dto.ReservaRifa;
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

@WebMvcTest(VentaRifaController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class VentaRifaControllerTest {

    private static final Instant AHORA = Instant.parse("2026-10-28T12:00:00Z");

    @Autowired private MockMvc mockMvc;

    @MockitoBean private VentaRifaService ventaRifaService;
    @MockitoBean private CurrentUserProvider currentUserProvider;

    @Test
    @WithMockUser
    @DisplayName("POST /api/v1/rifas/{id}/reservas reserva los números del usuario de la sesión")
    void reserva() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(10L));
        when(ventaRifaService.reservar(10L, 1L, List.of(8, 7))).thenReturn(new ReservaRifa(1L, List.of(7, 8),
                new BigDecimal("500.00"), new BigDecimal("1000.00"), new BigDecimal("10.00"), new BigDecimal("100.00"),
                new BigDecimal("1100.00"), AHORA));

        mockMvc.perform(post("/api/v1/rifas/1/reservas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"numeros": [8, 7]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numeros[0]").value(7))
                .andExpect(jsonPath("$.total").value(1100.00))
                .andExpect(jsonPath("$.venceEn").value("2026-10-28T12:00:00Z"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/v1/rifas/{id}/reservas sin números responde 400 sin llamar al servicio")
    void reservaSinNumeros() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(10L));

        mockMvc.perform(post("/api/v1/rifas/1/reservas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"numeros": []}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"));
        verify(ventaRifaService, never()).reservar(any(), any(), any());
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/v1/rifas/{id}/reservas con un número ocupado responde 409 NUMERO_NO_DISPONIBLE con la lista")
    void reservaOcupada() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(10L));
        when(ventaRifaService.reservar(10L, 1L, List.of(7))).thenThrow(
                new ConflictoException(CodigoError.NUMERO_NO_DISPONIBLE, "Ocupado").con("numeros", List.of(7)));

        mockMvc.perform(post("/api/v1/rifas/1/reservas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"numeros": [7]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("NUMERO_NO_DISPONIBLE"))
                .andExpect(jsonPath("$.numeros[0]").value(7));
    }

    @Test
    @WithMockUser
    @DisplayName("DELETE /api/v1/rifas/{id}/reservas libera la reserva y responde 204")
    void cancelaReserva() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(10L));

        mockMvc.perform(delete("/api/v1/rifas/1/reservas"))
                .andExpect(status().isNoContent());
        verify(ventaRifaService).cancelarReserva(10L, 1L);
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/v1/rifas/{id}/comprar pasa la clave del header Idempotency-Key y devuelve el ticket")
    void compra() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(10L));
        when(ventaRifaService.comprar(eq(10L), eq(1L), eq(List.of(7)), eq("clave-1"))).thenReturn(
                new VentaRifaResultado(100L, "R1-4F2K9Q", List.of(7), new BigDecimal("500.00"),
                        new BigDecimal("50.00"), new BigDecimal("550.00"), AHORA, new BigDecimal("2000.00"), null));

        mockMvc.perform(post("/api/v1/rifas/1/comprar")
                        .header("Idempotency-Key", "clave-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"numeros": [7]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketCodigo").value("R1-4F2K9Q"))
                .andExpect(jsonPath("$.gastosAdministrativos").value(50.00))
                .andExpect(jsonPath("$.total").value(550.00));
    }

    @Test
    @DisplayName("Reservar y comprar sin sesión responde 401")
    void sinSesion() throws Exception {
        mockMvc.perform(post("/api/v1/rifas/1/reservas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"numeros": [7]}
                                """))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/rifas/1/comprar")
                        .header("Idempotency-Key", "clave-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"numeros": [7]}
                                """))
                .andExpect(status().isUnauthorized());
    }
}
