package com.hydra.pica.plataforma_pica.billetera.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.billetera.domain.EstadoCustodia;
import com.hydra.pica.plataforma_pica.billetera.dto.ComprobantePago;
import com.hydra.pica.plataforma_pica.billetera.dto.CustodiaResumen;
import com.hydra.pica.plataforma_pica.billetera.dto.RepartoLiquidacion;
import com.hydra.pica.plataforma_pica.billetera.dto.RepartoLiquidacion.Origen;
import com.hydra.pica.plataforma_pica.billetera.service.Acreditacion.Rol;
import com.hydra.pica.plataforma_pica.billetera.service.ComprobanteService;
import com.hydra.pica.plataforma_pica.billetera.service.CustodiaService;
import com.hydra.pica.plataforma_pica.billetera.service.FondosEnCustodiaException;
import com.hydra.pica.plataforma_pica.billetera.service.RepartoLiquidacionService;
import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CustodiaController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class CustodiaControllerTest {

    private static final Long USUARIO_ID = 42L;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private CustodiaService custodiaService;
    @MockitoBean private ComprobanteService comprobanteService;
    @MockitoBean private RepartoLiquidacionService repartoLiquidacionService;
    @MockitoBean private CurrentUserProvider currentUserProvider;

    @BeforeEach
    void sesion() {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(USUARIO_ID));
    }

    @Test
    void sinSesionEs401() throws Exception {
        mockMvc.perform(get("/api/v1/torneos/7/custodia")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/torneos/7/custodia/retiro")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/inscripciones/3/comprobante")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/torneos/7/liquidacion")).andExpect(status().isUnauthorized());

        verifyNoInteractions(custodiaService, comprobanteService, repartoLiquidacionService);
    }

    @Test
    @WithMockUser
    void elOrganizadorVeLaCustodiaDeSuTorneo() throws Exception {
        when(custodiaService.resumenDelTorneo(USUARIO_ID, 7L)).thenReturn(new CustodiaResumen(EstadoCustodia.RETENIDA,
                new BigDecimal("1100.00"), new BigDecimal("1000.00"), new BigDecimal("100.00"), 1, "ARS", false, null));

        mockMvc.perform(get("/api/v1/torneos/7/custodia"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RETENIDA"))
                .andExpect(jsonPath("$.total").value(1100.00))
                .andExpect(jsonPath("$.recaudado").value(1000.00))
                .andExpect(jsonPath("$.gastosAdministrativos").value(100.00))
                .andExpect(jsonPath("$.pagos").value(1))
                .andExpect(jsonPath("$.eventoTerminado").value(false));
    }

    @Test
    @WithMockUser
    void retirarAntesDelCierreEs409FondosEnCustodia() throws Exception {
        doThrow(new FondosEnCustodiaException("en custodia")).when(custodiaService).retirarDelTorneo(USUARIO_ID, 7L);

        mockMvc.perform(post("/api/v1/torneos/7/custodia/retiro"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("FONDOS_EN_CUSTODIA"));
    }

    @Test
    @WithMockUser
    void retirarDeLaRifaAntesDelCierreEs409FondosEnCustodia() throws Exception {
        doThrow(new FondosEnCustodiaException("en custodia")).when(custodiaService).retirarDeLaRifa(USUARIO_ID, 9L);

        mockMvc.perform(post("/api/v1/rifas/9/custodia/retiro"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("FONDOS_EN_CUSTODIA"));
    }

    @Test
    @WithMockUser
    void elOrganizadorVeElRepartoDeLaLiquidacion() throws Exception {
        when(repartoLiquidacionService.delTorneo(USUARIO_ID, 7L)).thenReturn(new RepartoLiquidacion(true,
                Instant.parse("2026-11-09T12:00:00Z"), new BigDecimal("1100.00"), "ARS", List.of(
                        new RepartoLiquidacion.Linea(Rol.PREMIO, Origen.TORNEO, 1, "Juan Uno", "dev_jugador1", null,
                                new BigDecimal("1000.00"), Instant.parse("2026-11-09T12:00:00Z")),
                        new RepartoLiquidacion.Linea(Rol.PLATAFORMA, Origen.TORNEO, null, null, null, null,
                                new BigDecimal("100.00"), Instant.parse("2026-11-09T12:00:00Z")))));

        mockMvc.perform(get("/api/v1/torneos/7/liquidacion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liquidado").value(true))
                .andExpect(jsonPath("$.total").value(1100.00))
                .andExpect(jsonPath("$.lineas[0].rol").value("PREMIO"))
                .andExpect(jsonPath("$.lineas[0].puesto").value(1))
                .andExpect(jsonPath("$.lineas[0].beneficiarioUsername").value("dev_jugador1"))
                .andExpect(jsonPath("$.lineas[1].rol").value("PLATAFORMA"))
                .andExpect(jsonPath("$.lineas[1].beneficiarioNombre").doesNotExist());
    }

    @Test
    @WithMockUser
    void elRepartoDeUnaRifaAjenaEs404() throws Exception {
        when(repartoLiquidacionService.deLaRifa(USUARIO_ID, 9L))
                .thenThrow(new NoEncontradoException(CodigoError.RIFA_NO_ENCONTRADA, "no existe"));

        mockMvc.perform(get("/api/v1/rifas/9/liquidacion"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("RIFA_NO_ENCONTRADA"));
    }

    @Test
    @WithMockUser
    void quienPagoVeElComprobante() throws Exception {
        when(comprobanteService.deInscripcion(USUARIO_ID, 3L)).thenReturn(new ComprobantePago("I7-55",
                Instant.parse("2026-11-08T12:00:00Z"), "Juan Uno", "dev_jugador1", "Inscripción al torneo Copa", 7L,
                "Copa", null, new BigDecimal("1000.00"), new BigDecimal("100.00"), new BigDecimal("1100.00"), "ARS",
                EstadoCustodia.RETENIDA));

        mockMvc.perform(get("/api/v1/inscripciones/3/comprobante"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numero").value("I7-55"))
                .andExpect(jsonPath("$.total").value(1100.00))
                .andExpect(jsonPath("$.custodia").value("RETENIDA"));
    }

    @Test
    @WithMockUser
    void sinPagoElComprobanteEs404() throws Exception {
        when(comprobanteService.deInscripcion(USUARIO_ID, 3L))
                .thenThrow(new NoEncontradoException(CodigoError.COMPROBANTE_NO_ENCONTRADO, "sin pago"));

        mockMvc.perform(get("/api/v1/inscripciones/3/comprobante"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("COMPROBANTE_NO_ENCONTRADO"));
    }
}
