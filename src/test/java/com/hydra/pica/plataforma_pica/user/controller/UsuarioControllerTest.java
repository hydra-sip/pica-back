package com.hydra.pica.plataforma_pica.user.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioPublico;
import com.hydra.pica.plataforma_pica.user.service.UsuarioBusquedaService;
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

/** GET /api/v1/usuarios (PICA-208): cualquier usuario logueado busca jugadores; la consulta está en el servicio. */
@WebMvcTest(UsuarioController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class UsuarioControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UsuarioBusquedaService usuarioBusquedaService;

    @Test
    @DisplayName("Sin autenticación responde 401")
    void sinAutenticacion() throws Exception {
        mockMvc.perform(get("/api/v1/usuarios").param("q", "perez"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(usuarioBusquedaService);
    }

    @Test
    @WithMockUser
    @DisplayName("Un usuario logueado sin permisos busca y recibe id, username, nombres y apellidos, sin email")
    void buscaSinPermisoEspecial() throws Exception {
        PageRequest pagina = PageRequest.of(1, 5);
        when(usuarioBusquedaService.buscarJugadores("perez", 1, 5))
                .thenReturn(new PageImpl<>(List.of(new UsuarioPublico(7L, "jperez", "Juan", "Pérez")), pagina, 6));

        mockMvc.perform(get("/api/v1/usuarios").param("q", "perez").param("page", "1").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(7))
                .andExpect(jsonPath("$.content[0].username").value("jperez"))
                .andExpect(jsonPath("$.content[0].nombres").value("Juan"))
                .andExpect(jsonPath("$.content[0].apellidos").value("Pérez"))
                .andExpect(jsonPath("$.content[0].email").doesNotExist())
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.size").value(5))
                .andExpect(jsonPath("$.page.totalElements").value(6));
    }

    @Test
    @WithMockUser
    @DisplayName("Sin q, o con q en blanco, responde 400 REQUERIDO: no se lista a todos los usuarios")
    void sinQResponde400() throws Exception {
        mockMvc.perform(get("/api/v1/usuarios"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("q"))
                .andExpect(jsonPath("$.errores[0].codigo").value("REQUERIDO"));
        mockMvc.perform(get("/api/v1/usuarios").param("q", "  "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores.length()").value(1))
                .andExpect(jsonPath("$.errores[0].codigo").value("REQUERIDO"));
        verifyNoInteractions(usuarioBusquedaService);
    }

    @Test
    @WithMockUser
    @DisplayName("Con más de 100 caracteres en q o size mayor a 100 responde 400")
    void limitesDelContrato() throws Exception {
        mockMvc.perform(get("/api/v1/usuarios").param("q", "a".repeat(101)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/usuarios").param("q", "perez").param("size", "101"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(usuarioBusquedaService);
    }
}
