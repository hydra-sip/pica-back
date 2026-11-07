package com.hydra.pica.plataforma_pica.control.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.control.domain.ClaveParametro;
import com.hydra.pica.plataforma_pica.control.dto.ParametroDetalle;
import com.hydra.pica.plataforma_pica.control.service.ParametrosAdminService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ParametroAdminController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class ParametroAdminControllerTest {

    private static final Long ADMIN = 1L;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ParametrosAdminService parametrosAdminService;
    @MockitoBean private CurrentUserProvider currentUserProvider;

    private static ParametroDetalle gastos(String valor) {
        return new ParametroDetalle("GASTOS_ADMINISTRATIVOS", ClaveParametro.Unidad.PORCENTAJE, new BigDecimal(valor),
                "% de gastos administrativos", Instant.parse("2026-11-05T12:00:00Z"), "admin");
    }

    @Test
    @DisplayName("Sin sesión responde 401")
    void sinSesion() throws Exception {
        mockMvc.perform(get("/api/v1/admin/parametros")).andExpect(status().isUnauthorized());
        verifyNoInteractions(parametrosAdminService);
    }

    @Test
    @WithMockUser(authorities = "ROL_VER")
    @DisplayName("Con otro permiso del admin es 403 SIN_PERMISO")
    void otroPermiso() throws Exception {
        mockMvc.perform(get("/api/v1/admin/parametros"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
        mockMvc.perform(put("/api/v1/admin/parametros").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valores\": {\"GASTOS_ADMINISTRATIVOS\": 12}}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(parametrosAdminService);
    }

    @Test
    @WithMockUser(authorities = "PARAMETRO_ADMINISTRAR")
    @DisplayName("GET /admin/parametros lista los parámetros con su unidad")
    void listar() throws Exception {
        when(parametrosAdminService.listar()).thenReturn(List.of(gastos("10")));

        mockMvc.perform(get("/api/v1/admin/parametros"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].clave").value("GASTOS_ADMINISTRATIVOS"))
                .andExpect(jsonPath("$[0].unidad").value("PORCENTAJE"))
                .andExpect(jsonPath("$[0].valor").value(10))
                .andExpect(jsonPath("$[0].modificadoPor").value("admin"));
    }

    @Test
    @WithMockUser(authorities = "PARAMETRO_ADMINISTRAR")
    @DisplayName("PUT /admin/parametros pasa quién cambia y los valores por clave")
    void actualizar() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ADMIN));
        when(parametrosAdminService.actualizar(ADMIN, Map.of("GASTOS_ADMINISTRATIVOS", new BigDecimal("12.5"))))
                .thenReturn(List.of(gastos("12.5")));

        mockMvc.perform(put("/api/v1/admin/parametros").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valores\": {\"GASTOS_ADMINISTRATIVOS\": 12.5}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].valor").value(12.5));
        verify(parametrosAdminService).actualizar(ADMIN, Map.of("GASTOS_ADMINISTRATIVOS", new BigDecimal("12.5")));
    }

    @Test
    @WithMockUser(authorities = "PARAMETRO_ADMINISTRAR")
    @DisplayName("Sin valores es 400, y los errores del servicio vuelven por clave")
    void errores() throws Exception {
        mockMvc.perform(put("/api/v1/admin/parametros").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valores\": {}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"));

        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ADMIN));
        when(parametrosAdminService.actualizar(ADMIN, Map.of("CHECKIN_DURACION_MAX_MIN", BigDecimal.ZERO))).thenThrow(
                new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay parámetros inválidos")
                        .con("errores", List.of(new ErrorCampo("CHECKIN_DURACION_MAX_MIN",
                                ErrorCampo.Codigo.RANGO_INVALIDO, "Tiene que ser mayor que 0"))));

        mockMvc.perform(put("/api/v1/admin/parametros").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valores\": {\"CHECKIN_DURACION_MAX_MIN\": 0}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("CHECKIN_DURACION_MAX_MIN"))
                .andExpect(jsonPath("$.errores[0].codigo").value("RANGO_INVALIDO"));
    }
}
