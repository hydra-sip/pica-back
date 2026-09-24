package com.hydra.pica.plataforma_pica.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.error.ProhibidoException;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.dto.PersonaDatos;
import com.hydra.pica.plataforma_pica.user.dto.PersonaUsuario;
import com.hydra.pica.plataforma_pica.user.dto.RolMinimo;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioDetalle;
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
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

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
    @DisplayName("GET /api/v1/admin/usuarios/{id} devuelve el detalle del usuario")
    void verDetalleDevuelveElUsuario() throws Exception {
        UsuarioDetalle detalle = new UsuarioDetalle(
                1L, "jperez", "jperez@example.com", null, EstadoUsuario.ACTIVO,
                true, false, null, false, true, false,
                new PersonaDatos(10L, "Juan", "Pérez", "DNI", "12345678", null, null, null, null,
                        EstadoGeneral.ACTIVO),
                List.of(new RolMinimo(2L, "PARTICIPANTE", "Participante")),
                Instant.parse("2026-01-01T00:00:00Z"), null);

        when(usuarioAdminService.obtenerDetalle(1L)).thenReturn(detalle);

        mockMvc.perform(get("/api/v1/admin/usuarios/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("jperez"))
                .andExpect(jsonPath("$.persona.nombres").value("Juan"))
                .andExpect(jsonPath("$.roles[0].nombre").value("PARTICIPANTE"));
    }

    @Test
    @WithMockUser(authorities = "USUARIO_VER")
    @DisplayName("GET /api/v1/admin/usuarios/{id} con un id inexistente responde 404")
    void verDetalleInexistenteResponde404() throws Exception {
        when(usuarioAdminService.obtenerDetalle(99L))
                .thenThrow(new NoEncontradoException(CodigoError.USUARIO_NO_ENCONTRADO, "No existe el usuario 99"));

        mockMvc.perform(get("/api/v1/admin/usuarios/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("USUARIO_NO_ENCONTRADO"));
    }

    @Test
    @WithMockUser(authorities = "USUARIO_CREAR")
    @DisplayName("POST /api/v1/admin/usuarios crea un usuario y responde 201")
    void crearUsuarioResponde201() throws Exception {
        UsuarioDetalle creado = new UsuarioDetalle(
                5L, "nuevo", "nuevo@example.com", null, EstadoUsuario.ACTIVO,
                true, false, null, false, true, false,
                new PersonaDatos(10L, "Ana", "Gomez", "DNI", "111", null, null, null, null, EstadoGeneral.ACTIVO),
                List.of(), Instant.parse("2026-01-01T00:00:00Z"), null);

        when(usuarioAdminService.crear(any())).thenReturn(creado);

        mockMvc.perform(post("/api/v1/admin/usuarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "nuevo",
                                  "email": "nuevo@example.com",
                                  "passwordTemporal": "Password1",
                                  "personaId": 10,
                                  "roles": []
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.username").value("nuevo"));
    }

    @Test
    @WithMockUser(authorities = "USUARIO_CREAR")
    @DisplayName("POST /api/v1/admin/usuarios sin personaId responde 400")
    void crearSinPersonaIdResponde400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/usuarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "nuevo",
                                  "email": "nuevo@example.com",
                                  "passwordTemporal": "Password1",
                                  "roles": []
                                }
                                """))
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

    @Test
    @WithMockUser(authorities = "USUARIO_CREAR")
    @DisplayName("POST /api/v1/admin/usuarios con una contraseña sin mayúscula ni número responde 400 PASSWORD_DEBIL")
    void crearConContrasenaDebilResponde400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/usuarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(altaCon("\"passwordTemporal\": \"password\"", "\"personaId\": 10", "\"roles\": []")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores[0].campo").value("passwordTemporal"))
                .andExpect(jsonPath("$.errores[0].codigo").value("PASSWORD_DEBIL"));
    }

    @Test
    @WithMockUser(authorities = "USUARIO_CREAR")
    @DisplayName("POST /api/v1/admin/usuarios con un id de rol null responde 400")
    void crearConRolNullResponde400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/usuarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(altaCon("\"passwordTemporal\": \"Password1\"", "\"personaId\": 10", "\"roles\": [null]")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = "USUARIO_CREAR")
    @DisplayName("POST /api/v1/admin/usuarios con ids no positivos (persona o rol) responde 400")
    void crearConIdsNoPositivosResponde400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/usuarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(altaCon("\"passwordTemporal\": \"Password1\"", "\"personaId\": 0", "\"roles\": []")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/admin/usuarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(altaCon("\"passwordTemporal\": \"Password1\"", "\"personaId\": 10", "\"roles\": [-1]")))
                .andExpect(status().isBadRequest());
    }

    private static String altaCon(String password, String personaId, String roles) {
        return "{\"username\": \"nuevo\", \"email\": \"nuevo@example.com\", " + password + ", "
                + personaId + ", " + roles + "}";
    }

    @Test
    @WithMockUser(authorities = "USUARIO_VER")
    @DisplayName("POST /api/v1/admin/usuarios sin el permiso USUARIO_CREAR responde 403")
    void crearSinElPermisoResponde403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/usuarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "nuevo",
                                  "email": "nuevo@example.com",
                                  "passwordTemporal": "Password1",
                                  "personaId": 10,
                                  "roles": []
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    // --- PUT /{id}/roles (PICA-127) -------------------------------------------

    @Test
    @DisplayName("PUT /api/v1/admin/usuarios/{id}/roles sin autenticación responde 401")
    void reemplazarRolesSinAutenticacionResponde401() throws Exception {
        mockMvc.perform(putRoles(3L, "{\"roles\": [2]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(authorities = {"USUARIO_VER", "USUARIO_EDITAR", "ROL_VER"})
    @DisplayName("PUT /api/v1/admin/usuarios/{id}/roles sin ROL_ASIGNAR responde 403")
    void reemplazarRolesSinElPermisoResponde403() throws Exception {
        mockMvc.perform(putRoles(3L, "{\"roles\": [2]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
    }

    @Test
    @WithMockUser(authorities = "ROL_ASIGNAR")
    @DisplayName("PUT /api/v1/admin/usuarios/{id}/roles con ROL_ASIGNAR devuelve el usuario con sus roles")
    void reemplazarRolesDevuelveElDetalle() throws Exception {
        UsuarioDetalle detalle = new UsuarioDetalle(
                3L, "juan", "juan@example.com", null, EstadoUsuario.ACTIVO,
                true, false, null, false, true, false,
                new PersonaDatos(10L, "Juan", "Pérez", "DNI", "12345678", null, null, null, null, EstadoGeneral.ACTIVO),
                List.of(new RolMinimo(5L, "SOPORTE", "Soporte")), Instant.parse("2026-01-01T00:00:00Z"), null);
        when(usuarioAdminService.reemplazarRoles(eq(3L), eq(List.of(5L, 5L)))).thenReturn(detalle);

        mockMvc.perform(putRoles(3L, "{\"roles\": [5, 5]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.roles[0].nombre").value("SOPORTE"));
    }

    @Test
    @WithMockUser(authorities = "ROL_ASIGNAR")
    @DisplayName("PUT /api/v1/admin/usuarios/{id}/roles sin la lista o con un id nulo o no positivo responde 400")
    void reemplazarRolesSinListaResponde400() throws Exception {
        mockMvc.perform(putRoles(3L, "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores[0].campo").value("roles"));
        mockMvc.perform(putRoles(3L, "{\"roles\": [null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"));
        mockMvc.perform(putRoles(3L, "{\"roles\": [0]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"));
    }

    @Test
    @WithMockUser(authorities = "ROL_ASIGNAR")
    @DisplayName("PUT /api/v1/admin/usuarios/{id}/roles: una protección del servicio sale como 403 con su código")
    void reemplazarRolesProtegidoResponde403() throws Exception {
        when(usuarioAdminService.reemplazarRoles(eq(3L), any()))
                .thenThrow(new ProhibidoException(CodigoError.ULTIMO_ASIGNADOR, "No podés"));

        mockMvc.perform(putRoles(3L, "{\"roles\": []}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("ULTIMO_ASIGNADOR"));
    }

    private static RequestBuilder putRoles(Long id, String body) {
        return put("/api/v1/admin/usuarios/{id}/roles", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
