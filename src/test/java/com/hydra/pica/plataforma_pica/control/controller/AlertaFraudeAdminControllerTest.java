package com.hydra.pica.plataforma_pica.control.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.control.domain.EstadoAlertaFraude;
import com.hydra.pica.plataforma_pica.control.domain.ReglaFraude;
import com.hydra.pica.plataforma_pica.control.dto.AlertaFraudeDetalle;
import com.hydra.pica.plataforma_pica.control.dto.AlertaFraudeDetalle.RifaAlertaResumen;
import com.hydra.pica.plataforma_pica.control.dto.AlertaFraudeDetalle.TorneoAlertaResumen;
import com.hydra.pica.plataforma_pica.control.dto.AlertaFraudeDetalle.UsuarioAlertaResumen;
import com.hydra.pica.plataforma_pica.control.dto.RevisarAlertaFraudeRequest;
import com.hydra.pica.plataforma_pica.control.service.AlertaFraudeAdminService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AlertaFraudeAdminController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class AlertaFraudeAdminControllerTest {

    private static final Instant DESDE = Instant.parse("2026-11-10T00:00:00Z");
    private static final Instant HASTA = Instant.parse("2026-11-11T00:00:00Z");
    private static final Long SOPORTE = 99L;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private AlertaFraudeAdminService alertaFraudeAdminService;
    @MockitoBean private CurrentUserProvider currentUserProvider;

    private static AlertaFraudeDetalle alertaPendiente() {
        return new AlertaFraudeDetalle(
                15L,
                ReglaFraude.COMPRA_MASIVA_RIFA,
                EstadoAlertaFraude.PENDIENTE,
                "Compra masiva en la rifa",
                Map.of("numerosComprados", 12, "umbralNumeros", 10),
                new TorneoAlertaResumen(4L, "Copa Primavera"),
                new RifaAlertaResumen(8L, "Rifa Solidaria"),
                new UsuarioAlertaResumen(3L, "comprador1", "comprador1@example.com"),
                null,
                null,
                null,
                DESDE);
    }

    @Test
    @DisplayName("Sin sesión responde 401")
    void sinSesion() throws Exception {
        mockMvc.perform(get("/api/v1/admin/alertas-fraude")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/alertas-fraude/15")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin/alertas-fraude/15/revisar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nota\":\"Revisada\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(alertaFraudeAdminService);
    }

    @Test
    @WithMockUser(authorities = "AUDITORIA_VER")
    @DisplayName("Sin ALERTA_FRAUDE_REVISAR responde 403 SIN_PERMISO")
    void sinPermiso() throws Exception {
        mockMvc.perform(get("/api/v1/admin/alertas-fraude"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
        mockMvc.perform(post("/api/v1/admin/alertas-fraude/15/revisar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nota\":\"Revisada\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
        verifyNoInteractions(alertaFraudeAdminService);
    }

    @Test
    @WithMockUser(authorities = "ALERTA_FRAUDE_REVISAR")
    @DisplayName("GET /admin/alertas-fraude pasa los filtros y devuelve la página")
    void listar() throws Exception {
        when(alertaFraudeAdminService.listar(
                EstadoAlertaFraude.PENDIENTE, ReglaFraude.COMPRA_MASIVA_RIFA, 4L, 8L, 3L, DESDE, HASTA, 1, 10))
                .thenReturn(new PageImpl<>(List.of(alertaPendiente()), PageRequest.of(1, 10), 11));

        mockMvc.perform(get("/api/v1/admin/alertas-fraude")
                        .param("estado", "PENDIENTE")
                        .param("regla", "COMPRA_MASIVA_RIFA")
                        .param("torneoId", "4")
                        .param("rifaId", "8")
                        .param("usuarioId", "3")
                        .param("desde", "2026-11-10T00:00:00Z")
                        .param("hasta", "2026-11-11T00:00:00Z")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(15))
                .andExpect(jsonPath("$.content[0].regla").value("COMPRA_MASIVA_RIFA"))
                .andExpect(jsonPath("$.content[0].estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.content[0].usuario.username").value("comprador1"))
                .andExpect(jsonPath("$.content[0].rifa.titulo").value("Rifa Solidaria"))
                .andExpect(jsonPath("$.content[0].torneo.nombre").value("Copa Primavera"))
                .andExpect(jsonPath("$.page.totalElements").value(11));
        verify(alertaFraudeAdminService).listar(
                EstadoAlertaFraude.PENDIENTE, ReglaFraude.COMPRA_MASIVA_RIFA, 4L, 8L, 3L, DESDE, HASTA, 1, 10);
    }

    @Test
    @WithMockUser(authorities = "ALERTA_FRAUDE_REVISAR")
    @DisplayName("GET /admin/alertas-fraude acepta alias torneo/rifa/usuario y valida enums/fechas")
    void filtrosAliasYValidacion() throws Exception {
        when(alertaFraudeAdminService.listar(null, null, 4L, 8L, 3L, null, null, 0, 20))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/v1/admin/alertas-fraude")
                        .param("torneo", "4")
                        .param("rifa", "8")
                        .param("usuario", "3"))
                .andExpect(status().isOk());
        verify(alertaFraudeAdminService).listar(null, null, 4L, 8L, 3L, null, null, 0, 20);

        mockMvc.perform(get("/api/v1/admin/alertas-fraude").param("regla", "INEXISTENTE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"));
    }

    @Test
    @WithMockUser(authorities = "ALERTA_FRAUDE_REVISAR")
    @DisplayName("GET /{id} y POST /{id}/revisar delegan al servicio")
    void detalleYRevisar() throws Exception {
        when(alertaFraudeAdminService.detalle(15L)).thenReturn(alertaPendiente());
        mockMvc.perform(get("/api/v1/admin/alertas-fraude/15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(15))
                .andExpect(jsonPath("$.datos.numerosComprados").value(12));

        AlertaFraudeDetalle revisada = new AlertaFraudeDetalle(
                15L, ReglaFraude.COMPRA_MASIVA_RIFA, EstadoAlertaFraude.REVISADA, "Compra masiva en la rifa",
                Map.of("numerosComprados", 12), null, null,
                new UsuarioAlertaResumen(3L, "comprador1", "comprador1@example.com"),
                "Usuario verificado", "soporte", HASTA, DESDE);
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(SOPORTE));
        when(alertaFraudeAdminService.revisar(SOPORTE, 15L, new RevisarAlertaFraudeRequest("Usuario verificado")))
                .thenReturn(revisada);

        mockMvc.perform(post("/api/v1/admin/alertas-fraude/15/revisar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nota\":\"Usuario verificado\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("REVISADA"))
                .andExpect(jsonPath("$.nota").value("Usuario verificado"))
                .andExpect(jsonPath("$.revisadaPor").value("soporte"));
    }
}
