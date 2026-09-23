package com.hydra.pica.plataforma_pica.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.dto.PersonaUsuario;
import com.hydra.pica.plataforma_pica.user.dto.RolMinimo;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioResumen;
import com.hydra.pica.plataforma_pica.user.service.UsuarioAdminService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminUsuarioController.class)
@Import({SecurityConfig.class, WebConfig.class})
class AdminUsuarioControllerTest {

    private final MockMvc mockMvc;

    @MockitoBean
    private UsuarioAdminService usuarioAdminService;

    @Autowired
    AdminUsuarioControllerTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    @DisplayName("GET /api/v1/admin/usuarios sin autenticación responde 401")
    void sinAutenticacionResponde401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/usuarios"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/admin/usuarios autenticado sin el permiso USUARIO_VER responde 403")
    @WithMockUser(authorities = {"PERSONA_VER"})
    void sinElPermisoResponde403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/usuarios"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "USUARIO_VER")
    @DisplayName("GET /api/v1/admin/usuarios devuelve una página de usuarios")
    void listaUsuariosPaginados() throws Exception {
        UsuarioResumen resumen = new UsuarioResumen(
                1L,
                "jperez",
                "jperez@example.com",
                EstadoUsuario.ACTIVO,
                false,
                false,
                new PersonaUsuario(10L, "Pérez, Juan", "DNI", "12345678"),
                List.of(new RolMinimo(2L, "PARTICIPANTE", "Participante")),
                Instant.parse("2026-01-01T00:00:00Z"));
        Page<UsuarioResumen> pagina = new PageImpl<>(List.of(resumen), PageRequest.of(0, 20), 1);

        when(usuarioAdminService.listar(any(), any(), any(), anyBoolean(), any())).thenReturn(pagina);

        mockMvc.perform(get("/api/v1/admin/usuarios").param("q", "perez"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].username").value("jperez"))
                .andExpect(jsonPath("$.content[0].persona.nombreCompleto").value("Pérez, Juan"))
                .andExpect(jsonPath("$.content[0].roles[0].nombre").value("PARTICIPANTE"))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.page.number").value(0));
    }

    @Test
    @WithMockUser(authorities = "USUARIO_VER")
    @DisplayName("GET /api/v1/admin/usuarios incluye usuarios eliminados con su payload completo")
    void listaUsuariosIncluyendoEliminadosDevuelvePayloadCompleto() throws Exception {
        UsuarioResumen resumen = new UsuarioResumen(
                2L,
                "usuario-eliminado",
                "eliminado@example.com",
                EstadoUsuario.BLOQUEADO,
                true,
                false,
                new PersonaUsuario(20L, "Gómez, Ana", "DNI", "87654321"),
                List.of(new RolMinimo(3L, "ADMINISTRADOR", "Administrador")),
                Instant.parse("2026-01-02T00:00:00Z"));
        Page<UsuarioResumen> pagina = new PageImpl<>(List.of(resumen), PageRequest.of(0, 20), 1);

        when(usuarioAdminService.listar(any(), any(), any(), anyBoolean(), any())).thenReturn(pagina);

        mockMvc.perform(get("/api/v1/admin/usuarios")
                        .param("incluirEliminados", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(2))
                .andExpect(jsonPath("$.content[0].username").value("usuario-eliminado"))
                .andExpect(jsonPath("$.content[0].email").value("eliminado@example.com"))
                .andExpect(jsonPath("$.content[0].estado").value("BLOQUEADO"))
                .andExpect(jsonPath("$.content[0].eliminado").value(true))
                .andExpect(jsonPath("$.content[0].persona.id").value(20))
                .andExpect(jsonPath("$.content[0].persona.nombreCompleto").value("Gómez, Ana"))
                .andExpect(jsonPath("$.content[0].persona.tipoDoc").value("DNI"))
                .andExpect(jsonPath("$.content[0].persona.nroDoc").value("87654321"))
                .andExpect(jsonPath("$.content[0].roles[0].id").value(3))
                .andExpect(jsonPath("$.content[0].roles[0].nombre").value("ADMINISTRADOR"))
                .andExpect(jsonPath("$.content[0].roles[0].nombreAmigable").value("Administrador"))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.page.number").value(0));
    }

    @Test
    @WithMockUser(authorities = "USUARIO_VER")
    @DisplayName("GET /api/v1/admin/usuarios rechaza size fuera de rango")
    void sizeInvalidoResponde400() throws Exception {
        mockMvc.perform(get("/api/v1/admin/usuarios").param("size", "500"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = "USUARIO_VER")
    @DisplayName("GET /api/v1/admin/usuarios rechaza una búsqueda q de más de 100 caracteres")
    void qDemasiadoLargaResponde400() throws Exception {
        mockMvc.perform(get("/api/v1/admin/usuarios").param("q", "a".repeat(101)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = "USUARIO_VER")
    @DisplayName("GET /api/v1/admin/usuarios acepta una búsqueda q de exactamente 100 caracteres")
    void qDeCienCaracteresEsValida() throws Exception {
        when(usuarioAdminService.listar(any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/v1/admin/usuarios").param("q", "a".repeat(100)))
                .andExpect(status().isOk());
    }
}
