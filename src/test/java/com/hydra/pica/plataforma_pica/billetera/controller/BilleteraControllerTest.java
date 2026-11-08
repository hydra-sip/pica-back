package com.hydra.pica.plataforma_pica.billetera.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.billetera.domain.EstadoBilletera;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.dto.BilleteraResponse;
import com.hydra.pica.plataforma_pica.billetera.dto.MovimientoBilleteraResponse;
import com.hydra.pica.plataforma_pica.billetera.dto.RifaMovimiento;
import com.hydra.pica.plataforma_pica.billetera.dto.TorneoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.service.BilleteraService;
import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BilleteraController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class BilleteraControllerTest {

    private static final Long USUARIO_ID = 42L;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private BilleteraService billeteraService;
    @MockitoBean private CurrentUserProvider currentUserProvider;

    @Test
    @DisplayName("GET /api/v1/billetera sin sesión responde 401 UNAUTHORIZED")
    void verBilleteraSinSesionDa401() throws Exception {
        mockMvc.perform(get("/api/v1/billetera"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(billeteraService);
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/v1/billetera con sesión devuelve los saldos y estado de la billetera")
    void verBilleteraConSesionDevuelveDatos() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(USUARIO_ID));
        when(billeteraService.obtenerBilletera(USUARIO_ID)).thenReturn(
                new BilleteraResponse(
                        new BigDecimal("25000.50"),
                        new BigDecimal("5000.00"),
                        new BigDecimal("12000.00"),
                        "ARS",
                        EstadoBilletera.ACTIVA));

        mockMvc.perform(get("/api/v1/billetera"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saldoDisponible").value(25000.50))
                .andExpect(jsonPath("$.saldoRetenido").value(5000.00))
                .andExpect(jsonPath("$.gananciaAcumulada").value(12000.00))
                .andExpect(jsonPath("$.moneda").value("ARS"))
                .andExpect(jsonPath("$.estado").value("ACTIVA"));

        verify(billeteraService).obtenerBilletera(USUARIO_ID);
    }

    @Test
    @DisplayName("GET /api/v1/billetera/movimientos sin sesión responde 401 UNAUTHORIZED")
    void listarMovimientosSinSesionDa401() throws Exception {
        mockMvc.perform(get("/api/v1/billetera/movimientos"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(billeteraService);
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/v1/billetera/movimientos con sesión devuelve movimientos paginados")
    void listarMovimientosConSesionDevuelvePagina() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(USUARIO_ID));

        Instant ahora = Instant.parse("2026-11-07T20:00:00Z");
        MovimientoBilleteraResponse mov1 = new MovimientoBilleteraResponse(
                101L,
                TipoMovimiento.CARGA,
                new BigDecimal("10000.00"),
                "ARS",
                new BigDecimal("10000.00"),
                EstadoMovimiento.CONFIRMADO,
                "sim-carga-1",
                "Carga de saldo",
                null,
                null,
                ahora);

        MovimientoBilleteraResponse mov2 = new MovimientoBilleteraResponse(
                102L,
                TipoMovimiento.INSCRIPCION,
                new BigDecimal("3000.00"),
                "ARS",
                new BigDecimal("7000.00"),
                EstadoMovimiento.CONFIRMADO,
                "sim-inscripcion-2",
                "Inscripción al torneo",
                new TorneoMovimiento(5L, "Torneo de Verano", "Torneo de Verano", "torneo-de-verano"),
                null,
                ahora.plusSeconds(60));

        MovimientoBilleteraResponse mov3 = new MovimientoBilleteraResponse(
                103L,
                TipoMovimiento.COMPRA_NUMERO,
                new BigDecimal("1500.00"),
                "ARS",
                new BigDecimal("5500.00"),
                EstadoMovimiento.CONFIRMADO,
                "sim-ticket-3",
                "Compra de 2 números",
                null,
                new RifaMovimiento(8L, "Rifa Anual"),
                ahora.plusSeconds(120));

        PageImpl<MovimientoBilleteraResponse> pagina = new PageImpl<>(
                List.of(mov3, mov2, mov1), PageRequest.of(0, 20), 3);

        when(billeteraService.listarMovimientos(eq(USUARIO_ID), any())).thenReturn(pagina);

        mockMvc.perform(get("/api/v1/billetera/movimientos?page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(103))
                .andExpect(jsonPath("$.content[0].tipo").value("COMPRA_NUMERO"))
                .andExpect(jsonPath("$.content[0].monto").value(1500.00))
                .andExpect(jsonPath("$.content[0].rifa.id").value(8))
                .andExpect(jsonPath("$.content[0].rifa.titulo").value("Rifa Anual"))
                .andExpect(jsonPath("$.content[1].id").value(102))
                .andExpect(jsonPath("$.content[1].tipo").value("INSCRIPCION"))
                .andExpect(jsonPath("$.content[1].torneo.id").value(5))
                .andExpect(jsonPath("$.content[1].torneo.slug").value("torneo-de-verano"))
                .andExpect(jsonPath("$.content[2].id").value(101))
                .andExpect(jsonPath("$.content[2].tipo").value("CARGA"))
                .andExpect(jsonPath("$.page.totalElements").value(3));

        verify(billeteraService).listarMovimientos(eq(USUARIO_ID), any());
    }
}

