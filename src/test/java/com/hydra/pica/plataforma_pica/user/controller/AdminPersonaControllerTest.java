package com.hydra.pica.plataforma_pica.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.security.JwtService;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.dto.PersonaDetalle;
import com.hydra.pica.plataforma_pica.user.dto.PersonaResumen;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioMinimo;
import com.hydra.pica.plataforma_pica.user.service.PersonaAdminService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(AdminPersonaController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class AdminPersonaControllerTest {

    private static final String URL = "/api/v1/admin/personas";

    private static final String ALTA = """
            {"nombres": "Juan", "apellidos": "Pérez", "tipoDoc": "DNI", "nroDoc": "30123456",
             "fechaNacimiento": "1990-05-20", "telefono": "1155551234"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PersonaAdminService personaAdminService;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private UserDetailsService userDetailsService;

    private static PersonaDetalle detalle(Long id, UsuarioMinimo usuario) {
        return new PersonaDetalle(id, "Juan", "Pérez", "DNI", "30123456", LocalDate.of(1990, 5, 20), null,
                "1155551234", null, EstadoGeneral.ACTIVO, false, null,
                Instant.parse("2026-01-01T00:00:00Z"), null, usuario);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    // --- seguridad ---------------------------------------------------------------

    @Test
    @DisplayName("Todas las operaciones sin autenticación responden 401")
    void sinAutenticacionResponde401() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(json(post(URL), ALTA)).andExpect(status().isUnauthorized());
        mockMvc.perform(json(put(URL + "/1"), ALTA)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(URL + "/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/1/reactivar")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(authorities = "USUARIO_VER")
    @DisplayName("Sin el permiso PERSONA_* que corresponde, todas las operaciones responden 403")
    void sinElPermisoResponde403() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isForbidden());
        mockMvc.perform(get(URL + "/1")).andExpect(status().isForbidden());
        mockMvc.perform(json(post(URL), ALTA)).andExpect(status().isForbidden());
        mockMvc.perform(json(put(URL + "/1"), ALTA)).andExpect(status().isForbidden());
        mockMvc.perform(delete(URL + "/1")).andExpect(status().isForbidden());
        mockMvc.perform(post(URL + "/1/reactivar")).andExpect(status().isForbidden());
        verifyNoInteractions(personaAdminService);
    }

    @Test
    @WithMockUser(authorities = {"PERSONA_VER", "PERSONA_CREAR", "PERSONA_EDITAR"})
    @DisplayName("Con PERSONA_VER, CREAR y EDITAR pero sin PERSONA_ELIMINAR, baja y reactivar responden 403")
    void bajaYReactivarPidenPersonaEliminar() throws Exception {
        mockMvc.perform(delete(URL + "/1")).andExpect(status().isForbidden());
        mockMvc.perform(post(URL + "/1/reactivar")).andExpect(status().isForbidden());
    }

    // --- listar ------------------------------------------------------------------

    @Test
    @WithMockUser(authorities = "PERSONA_VER")
    @DisplayName("GET /admin/personas devuelve la página con tieneUsuario y eliminado")
    void listar() throws Exception {
        PersonaResumen fila = new PersonaResumen(1L, "Juan", "Pérez", "DNI", "30123456",
                EstadoGeneral.ACTIVO, false, true);
        when(personaAdminService.listar(any(), any(), anyBoolean(), any()))
                .thenReturn(new PageImpl<>(List.of(fila), PageRequest.of(0, 20), 1));

        mockMvc.perform(get(URL).param("q", "perez"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].apellidos").value("Pérez"))
                .andExpect(jsonPath("$.content[0].tieneUsuario").value(true))
                .andExpect(jsonPath("$.content[0].eliminado").value(false))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    @WithMockUser(authorities = "PERSONA_VER")
    @DisplayName("GET /admin/personas pasa los filtros al servicio y ordena por apellidos y nombres si no piden otro orden")
    void listarConFiltros() throws Exception {
        when(personaAdminService.listar(any(), any(), anyBoolean(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get(URL).param("q", "gomez").param("estado", "INACTIVO")
                        .param("incluirEliminados", "true"))
                .andExpect(status().isOk());

        verify(personaAdminService).listar(eq("gomez"), eq(EstadoGeneral.INACTIVO), eq(true),
                eq(PageRequest.of(0, 20, org.springframework.data.domain.Sort.by("apellidos", "nombres"))));
    }

    @Test
    @WithMockUser(authorities = "PERSONA_VER")
    @DisplayName("GET /admin/personas rechaza size fuera de rango, q de más de 100 caracteres y estado desconocido")
    void listarConParametrosInvalidos() throws Exception {
        mockMvc.perform(get(URL).param("size", "500")).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL).param("q", "a".repeat(101))).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL).param("estado", "BLOQUEADO")).andExpect(status().isBadRequest());
    }

    // --- ver ---------------------------------------------------------------------

    @Test
    @WithMockUser(authorities = "PERSONA_VER")
    @DisplayName("GET /admin/personas/{id} devuelve la ficha con el usuario vinculado")
    void ver() throws Exception {
        UsuarioMinimo usuario = new UsuarioMinimo(5L, "juan", "juan@example.com", EstadoUsuario.ACTIVO, false);
        when(personaAdminService.obtener(1L)).thenReturn(detalle(1L, usuario));

        mockMvc.perform(get(URL + "/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nroDoc").value("30123456"))
                .andExpect(jsonPath("$.usuario.username").value("juan"))
                .andExpect(jsonPath("$.usuario.eliminado").value(false));
    }

    @Test
    @WithMockUser(authorities = "PERSONA_VER")
    @DisplayName("GET /admin/personas/{id} inexistente responde 404 PERSONA_NO_ENCONTRADA")
    void verInexistente() throws Exception {
        when(personaAdminService.obtener(99L))
                .thenThrow(new NoEncontradoException(CodigoError.PERSONA_NO_ENCONTRADA, "no existe"));

        mockMvc.perform(get(URL + "/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("PERSONA_NO_ENCONTRADA"));
    }

    // --- crear -------------------------------------------------------------------

    @Test
    @WithMockUser(authorities = "PERSONA_CREAR")
    @DisplayName("POST /admin/personas crea y responde 201")
    void crear() throws Exception {
        when(personaAdminService.crear(any())).thenReturn(detalle(7L, null));

        mockMvc.perform(json(post(URL), ALTA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.usuario").doesNotExist());
    }

    @Test
    @WithMockUser(authorities = "PERSONA_CREAR")
    @DisplayName("POST /admin/personas con datos inválidos responde 400 VALIDACION con el campo")
    void crearConDatosInvalidos() throws Exception {
        String[][] casos = {
                {"{\"apellidos\": \"Pérez\", \"tipoDoc\": \"DNI\", \"nroDoc\": \"30123456\"}", "nombres", "REQUERIDO"},
                {"{\"nombres\": \"Juan\", \"apellidos\": \"Pérez\", \"nroDoc\": \"30123456\"}", "tipoDoc", "REQUERIDO"},
                {"{\"nombres\": \"Juan\", \"apellidos\": \"Pérez\", \"tipoDoc\": \"DNI\"}", "nroDoc", "REQUERIDO"},
                {"{\"nombres\": \"Juan\", \"apellidos\": \"Pérez\", \"tipoDoc\": \"DNI\", \"nroDoc\": \"3012345A\"}",
                        "nroDoc", "FORMATO_INVALIDO"},
                {"{\"nombres\": \"Juan\", \"apellidos\": \"Pérez\", \"tipoDoc\": \"DNI\", \"nroDoc\": \"301234\"}",
                        "nroDoc", "FORMATO_INVALIDO"},
                {"{\"nombres\": \"Juan\", \"apellidos\": \"Pérez\", \"tipoDoc\": \"DNI\", \"nroDoc\": \"30123456\","
                        + " \"fechaNacimiento\": \"2999-01-01\"}", "fechaNacimiento", "FECHA_FUTURA"},
        };
        for (String[] caso : casos) {
            mockMvc.perform(json(post(URL), caso[0]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                    .andExpect(jsonPath("$.errores[0].campo").value(caso[1]))
                    .andExpect(jsonPath("$.errores[0].codigo").value(caso[2]));
        }
        verifyNoInteractions(personaAdminService);
    }

    @Test
    @WithMockUser(authorities = "PERSONA_CREAR")
    @DisplayName("POST /admin/personas con un documento repetido responde 409 DOCUMENTO_DUPLICADO")
    void crearDocumentoRepetido() throws Exception {
        when(personaAdminService.crear(any()))
                .thenThrow(new ConflictoException(CodigoError.DOCUMENTO_DUPLICADO, "ya existe"));

        mockMvc.perform(json(post(URL), ALTA))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("DOCUMENTO_DUPLICADO"));
    }

    // --- modificar ---------------------------------------------------------------

    @Test
    @WithMockUser(authorities = "PERSONA_EDITAR")
    @DisplayName("PUT /admin/personas/{id} modifica y devuelve la ficha; 409 si el documento es de otra")
    void modificar() throws Exception {
        when(personaAdminService.modificar(eq(1L), any())).thenReturn(detalle(1L, null));
        when(personaAdminService.modificar(eq(2L), any()))
                .thenThrow(new ConflictoException(CodigoError.DOCUMENTO_DUPLICADO, "ya existe"));

        mockMvc.perform(json(put(URL + "/1"), ALTA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
        mockMvc.perform(json(put(URL + "/2"), ALTA))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("DOCUMENTO_DUPLICADO"));
    }

    @Test
    @WithMockUser(authorities = "PERSONA_EDITAR")
    @DisplayName("PUT /admin/personas/{id} con una persona inexistente o dada de baja responde 404")
    void modificarInexistente() throws Exception {
        when(personaAdminService.modificar(eq(99L), any()))
                .thenThrow(new NoEncontradoException(CodigoError.PERSONA_NO_ENCONTRADA, "no existe"));

        mockMvc.perform(json(put(URL + "/99"), ALTA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("PERSONA_NO_ENCONTRADA"));
    }

    // --- eliminar y reactivar ----------------------------------------------------

    @Test
    @WithMockUser(authorities = "PERSONA_ELIMINAR")
    @DisplayName("DELETE /admin/personas/{id} responde 204")
    void eliminar() throws Exception {
        mockMvc.perform(delete(URL + "/1")).andExpect(status().isNoContent());

        verify(personaAdminService).eliminar(1L);
    }

    @Test
    @WithMockUser(authorities = "PERSONA_ELIMINAR")
    @DisplayName("DELETE /admin/personas/{id} con un usuario activo responde 409 PERSONA_CON_USUARIO")
    void eliminarConUsuarioActivo() throws Exception {
        doThrow(new ConflictoException(CodigoError.PERSONA_CON_USUARIO, "tiene usuario"))
                .when(personaAdminService).eliminar(1L);

        mockMvc.perform(delete(URL + "/1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("PERSONA_CON_USUARIO"));
    }

    @Test
    @WithMockUser(authorities = "PERSONA_ELIMINAR")
    @DisplayName("POST /admin/personas/{id}/reactivar devuelve la ficha; 404 si no existe")
    void reactivar() throws Exception {
        when(personaAdminService.reactivar(1L)).thenReturn(detalle(1L, null));
        when(personaAdminService.reactivar(99L))
                .thenThrow(new NoEncontradoException(CodigoError.PERSONA_NO_ENCONTRADA, "no existe"));

        mockMvc.perform(post(URL + "/1/reactivar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eliminado").value(false));
        mockMvc.perform(post(URL + "/99/reactivar")).andExpect(status().isNotFound());
    }
}
