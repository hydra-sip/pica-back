package com.hydra.pica.plataforma_pica.user.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.user.domain.Modulo;
import com.hydra.pica.plataforma_pica.user.dto.ModuloPermisos;
import com.hydra.pica.plataforma_pica.user.service.PermisoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * GET /admin/permisos con el servicio mockeado: permiso y forma del JSON (ModuloPermisos del
 * contrato). El agrupado contra el seed real está en {@code RolServiceIntegracionTest}.
 */
@WebMvcTest(PermisoAdminController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class PermisoAdminControllerTest {

    private final MockMvc mockMvc;

    @MockitoBean
    private PermisoService permisoService;

    @Autowired
    PermisoAdminControllerTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    @DisplayName("Con ROL_VER: el catálogo agrupado por módulo")
    @WithMockUser(authorities = "ROL_VER")
    void catalogoConRolVer() throws Exception {
        when(permisoService.catalogo()).thenReturn(List.of(new ModuloPermisos(Modulo.ROLES, List.of(
                new ModuloPermisos.Item("ROL_VER", "Ver roles y el catálogo de permisos")))));

        mockMvc.perform(get("/api/v1/admin/permisos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].modulo").value("ROLES"))
                .andExpect(jsonPath("$[0].permisos[0].codigo").value("ROL_VER"))
                .andExpect(jsonPath("$[0].permisos[0].descripcion").value("Ver roles y el catálogo de permisos"));
    }

    @Test
    @DisplayName("Sin ROL_VER: 403")
    @WithMockUser(authorities = {"USUARIO_VER", "PERSONA_VER"})
    void catalogoSinRolVerDa403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/permisos"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));

        verifyNoInteractions(permisoService);
    }
}
