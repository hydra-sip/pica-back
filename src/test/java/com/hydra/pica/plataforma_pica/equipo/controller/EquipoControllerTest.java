package com.hydra.pica.plataforma_pica.equipo.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.equipo.dto.EquipoResumen;
import com.hydra.pica.plataforma_pica.equipo.service.EquipoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** GET /api/v1/equipos/mios (PICA-234): el capitán sale de la sesión y las reglas están en EquipoService. */
@WebMvcTest(EquipoController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class EquipoControllerTest {

    private static final Long CAPITAN = 5L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EquipoService equipoService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @DisplayName("Sin autenticación responde 401")
    void sinAutenticacion() throws Exception {
        mockMvc.perform(get("/api/v1/equipos/mios"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(equipoService);
    }

    @Test
    @WithMockUser(authorities = "ROL_VER")
    @DisplayName("Sin INSCRIPCION_GESTIONAR responde 403 SIN_PERMISO")
    void sinPermiso() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(CAPITAN));

        mockMvc.perform(get("/api/v1/equipos/mios"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
        verifyNoInteractions(equipoService);
    }

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("GET /api/v1/equipos/mios devuelve la página del capitán de la sesión")
    void misEquipos() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(CAPITAN));
        when(equipoService.listarMios(eq(CAPITAN), any(Pageable.class))).thenReturn(new PageImpl<>(
                List.of(new EquipoResumen(7L, "Halcones")), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/equipos/mios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(7))
                .andExpect(jsonPath("$.content[0].nombre").value("Halcones"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }
}
