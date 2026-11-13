package com.hydra.pica.plataforma_pica.billetera.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.hydra.pica.plataforma_pica.billetera.dto.ConciliacionCargas;
import com.hydra.pica.plataforma_pica.billetera.dto.ConciliacionCargas.Estado;
import com.hydra.pica.plataforma_pica.billetera.dto.ConciliacionCargas.Registro;
import com.hydra.pica.plataforma_pica.billetera.service.ConciliacionService;
import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ConciliacionAdminController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class ConciliacionAdminControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ConciliacionService conciliacionService;

    @Test
    @WithMockUser(authorities = "USUARIO_VER")
    @DisplayName("Sin TORNEO_ADMINISTRAR es 403")
    void sinPermiso() throws Exception {
        mockMvc.perform(get("/api/v1/admin/conciliacion"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
        verifyNoInteractions(conciliacionService);
    }

    @Test
    @WithMockUser(authorities = "TORNEO_ADMINISTRAR")
    @DisplayName("Responde los totales y cada registro, con los montos en wei como texto")
    void conciliar() throws Exception {
        when(conciliacionService.conciliar()).thenReturn(new ConciliacionCargas(Instant.parse("2026-11-13T12:00:00Z"),
                55324950, 1, 1, 0, 0, 0, 1, List.of(new Registro(Estado.MONTO_DISTINTO, "0xabc", 1L, "ana", 10L,
                        new BigDecimal("100000.00"), "1000000000000000", "1", "0xdef", "0xdef", 57254703L, "0x262d"))));

        mockMvc.perform(get("/api/v1/admin/conciliacion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.desdeBloque").value(55324950))
                .andExpect(jsonPath("$.montosDistintos").value(1))
                .andExpect(jsonPath("$.registros[0].estado").value("MONTO_DISTINTO"))
                .andExpect(jsonPath("$.registros[0].montoWei").value("1000000000000000"))
                .andExpect(jsonPath("$.registros[0].montoWeiEnCadena").value("1"));
    }

    @Test
    @WithMockUser(authorities = "TORNEO_ADMINISTRAR")
    @DisplayName("Si no se pueden leer los eventos, 503 RED_NO_DISPONIBLE")
    void redNoDisponible() throws Exception {
        when(conciliacionService.conciliar()).thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                CodigoError.RED_NO_DISPONIBLE, "No se pudieron leer los eventos del contrato"));

        mockMvc.perform(get("/api/v1/admin/conciliacion"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.codigo").value("RED_NO_DISPONIBLE"));
    }
}
