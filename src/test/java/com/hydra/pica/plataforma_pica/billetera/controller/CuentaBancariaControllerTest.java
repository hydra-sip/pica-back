package com.hydra.pica.plataforma_pica.billetera.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.billetera.domain.EstadoValidacion;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoCuenta;
import com.hydra.pica.plataforma_pica.billetera.dto.CrearCuentaBancariaRequest;
import com.hydra.pica.plataforma_pica.billetera.dto.CuentaBancariaResponse;
import com.hydra.pica.plataforma_pica.billetera.service.CuentaBancariaService;
import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CuentaBancariaController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class CuentaBancariaControllerTest {

    private static final Long USUARIO_ID = 42L;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private CuentaBancariaService cuentaBancariaService;
    @MockitoBean private CurrentUserProvider currentUserProvider;

    @Test
    @DisplayName("GET /api/v1/billetera/cuentas sin sesión responde 401 UNAUTHORIZED")
    void listarSinSesionDa401() throws Exception {
        mockMvc.perform(get("/api/v1/billetera/cuentas"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(cuentaBancariaService);
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/v1/billetera/cuentas con sesión devuelve lista de cuentas")
    void listarConSesionDevuelveCuentas() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(USUARIO_ID));
        when(cuentaBancariaService.listar(USUARIO_ID)).thenReturn(List.of(
                new CuentaBancariaResponse(
                        10L,
                        TipoCuenta.CBU,
                        "2850590940090418135201",
                        "Marcos Paz",
                        "20-35123456-9",
                        EstadoValidacion.VALIDADA,
                        Instant.parse("2026-11-12T10:00:00Z"),
                        Instant.parse("2026-11-12T10:00:00Z"))));

        mockMvc.perform(get("/api/v1/billetera/cuentas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(10))
                .andExpect(jsonPath("$[0].tipo").value("CBU"))
                .andExpect(jsonPath("$[0].valor").value("2850590940090418135201"))
                .andExpect(jsonPath("$[0].titular").value("Marcos Paz"))
                .andExpect(jsonPath("$[0].cuitCuil").value("20-35123456-9"))
                .andExpect(jsonPath("$[0].estadoValidacion").value("VALIDADA"));

        verify(cuentaBancariaService).listar(USUARIO_ID);
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/v1/billetera/cuentas crea cuenta y responde 201 CREATED")
    void crearCuentaResponde201() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(USUARIO_ID));
        when(cuentaBancariaService.crear(eq(USUARIO_ID), any(CrearCuentaBancariaRequest.class))).thenReturn(
                new CuentaBancariaResponse(
                        11L,
                        TipoCuenta.ALIAS,
                        "mi.alias.pica",
                        "Marcos Paz",
                        "20-35123456-9",
                        EstadoValidacion.VALIDADA,
                        Instant.parse("2026-11-12T10:00:00Z"),
                        Instant.parse("2026-11-12T10:00:00Z")));

        mockMvc.perform(post("/api/v1/billetera/cuentas")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "tipo": "ALIAS",
                                  "valor": "mi.alias.pica",
                                  "titular": "Marcos Paz",
                                  "cuitCuil": "20-35123456-9"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.tipo").value("ALIAS"))
                .andExpect(jsonPath("$.valor").value("mi.alias.pica"))
                .andExpect(jsonPath("$.titular").value("Marcos Paz"))
                .andExpect(jsonPath("$.estadoValidacion").value("VALIDADA"));

        verify(cuentaBancariaService).crear(eq(USUARIO_ID), any(CrearCuentaBancariaRequest.class));
    }

    @Test
    @WithMockUser
    @DisplayName("DELETE /api/v1/billetera/cuentas/{id} responde 204 NO_CONTENT")
    void eliminarCuentaResponde204() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(USUARIO_ID));
        doNothing().when(cuentaBancariaService).eliminar(USUARIO_ID, 12L);

        mockMvc.perform(delete("/api/v1/billetera/cuentas/12"))
                .andExpect(status().isNoContent());

        verify(cuentaBancariaService).eliminar(USUARIO_ID, 12L);
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/v1/billetera/cuentas con datos inválidos responde 400 BAD_REQUEST")
    void crearCuentaInvalidaResponde400() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(USUARIO_ID));

        mockMvc.perform(post("/api/v1/billetera/cuentas")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "tipo": null,
                                  "valor": "",
                                  "titular": "",
                                  "cuitCuil": ""
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(cuentaBancariaService);
    }
}

