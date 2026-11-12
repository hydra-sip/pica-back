package com.hydra.pica.plataforma_pica.control.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.dto.RegistroAuditoria;
import com.hydra.pica.plataforma_pica.control.service.AuditoriaService;
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

@WebMvcTest(AuditoriaAdminController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class AuditoriaAdminControllerTest {

    private static final Instant DESDE = Instant.parse("2026-11-01T03:00:00Z");
    private static final Instant HASTA = Instant.parse("2026-11-02T03:00:00Z");

    @Autowired private MockMvc mockMvc;

    @MockitoBean private AuditoriaService auditoriaService;

    @Test
    @DisplayName("Sin sesión responde 401")
    void sinSesion() throws Exception {
        mockMvc.perform(get("/api/v1/admin/auditoria")).andExpect(status().isUnauthorized());
        verifyNoInteractions(auditoriaService);
    }

    @Test
    @WithMockUser(authorities = "USUARIO_VER")
    @DisplayName("Sin AUDITORIA_VER es 403 SIN_PERMISO")
    void sinPermiso() throws Exception {
        mockMvc.perform(get("/api/v1/admin/auditoria"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
        verifyNoInteractions(auditoriaService);
    }

    @Test
    @WithMockUser(authorities = "AUDITORIA_VER")
    @DisplayName("Pasa los filtros y la página al servicio, y responde la página con cada fila")
    void listar() throws Exception {
        RegistroAuditoria fila = new RegistroAuditoria(7L, DESDE, 3L, "arbitro", AccionAuditoria.LAUDO, "PARTIDO",
                "15", null, Map.of("resultado", "2-1"));
        when(auditoriaService.listar(4L, 3L, AccionAuditoria.LAUDO, DESDE, HASTA, 1, 10))
                .thenReturn(new PageImpl<>(List.of(fila), PageRequest.of(1, 10), 11));

        mockMvc.perform(get("/api/v1/admin/auditoria")
                        .param("torneo", "4").param("usuario", "3").param("accion", "LAUDO")
                        .param("desde", "2026-11-01T03:00:00Z").param("hasta", "2026-11-02T03:00:00Z")
                        .param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(7))
                .andExpect(jsonPath("$.content[0].fecha").value("2026-11-01T03:00:00Z"))
                .andExpect(jsonPath("$.content[0].username").value("arbitro"))
                .andExpect(jsonPath("$.content[0].accion").value("LAUDO"))
                .andExpect(jsonPath("$.content[0].entidad").value("PARTIDO"))
                .andExpect(jsonPath("$.content[0].entidadId").value("15"))
                .andExpect(jsonPath("$.content[0].detalle.resultado").value("2-1"))
                .andExpect(jsonPath("$.page.totalElements").value(11))
                .andExpect(jsonPath("$.page.number").value(1));
        verify(auditoriaService).listar(4L, 3L, AccionAuditoria.LAUDO, DESDE, HASTA, 1, 10);
    }

    @Test
    @WithMockUser(authorities = "AUDITORIA_VER")
    @DisplayName("Una acción que no existe o una fecha mal escrita es 400 VALIDACION")
    void parametrosInvalidos() throws Exception {
        mockMvc.perform(get("/api/v1/admin/auditoria").param("accion", "INVENTADA"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"));
        mockMvc.perform(get("/api/v1/admin/auditoria").param("desde", "ayer"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"));
        verifyNoInteractions(auditoriaService);
    }
}
