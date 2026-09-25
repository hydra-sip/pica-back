package com.hydra.pica.plataforma_pica.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ProhibidoException;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.dto.FiltroRoles;
import com.hydra.pica.plataforma_pica.user.dto.RolDetalle;
import com.hydra.pica.plataforma_pica.user.dto.RolRequest;
import com.hydra.pica.plataforma_pica.user.dto.RolResumen;
import com.hydra.pica.plataforma_pica.user.service.RolService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * La capa HTTP del ABM de roles con el servicio mockeado: qué permiso pide cada endpoint, la forma
 * de las respuestas según el contrato y los 400 de validación. Las reglas están probadas contra
 * Postgres en {@code RolServiceIntegracionTest}.
 */
@WebMvcTest(RolAdminController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class RolAdminControllerTest {

    private static final String ROLES = "/api/v1/admin/roles";
    private static final String BODY_VEEDOR = """
            {"nombre": "VEEDOR", "nombreAmigable": "Veedor", "descripcion": "Mira", "estado": "ACTIVO"}
            """;

    private final MockMvc mockMvc;

    @MockitoBean
    private RolService rolService;

    @Autowired
    RolAdminControllerTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    // --- listado ------------------------------------------------------------

    @Test
    @DisplayName("GET /roles: página con la forma del contrato y los filtros pasados al servicio")
    @WithMockUser(authorities = "ROL_VER")
    void listarDevuelveLaPaginaDelContrato() throws Exception {
        RolResumen arbitro = new RolResumen(4L, "ARBITRO", "Árbitro", null, EstadoGeneral.ACTIVO, false, false, 3);
        when(rolService.listar(any(), any())).thenReturn(new PageImpl<>(List.of(arbitro), PageRequest.of(1, 5), 6));

        mockMvc.perform(get(ROLES)
                        .param("q", "arbi")
                        .param("estado", "ACTIVO")
                        .param("incluirEliminados", "true")
                        .param("page", "1")
                        .param("size", "5")
                        .param("sort", "nombre,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(4))
                .andExpect(jsonPath("$.content[0].nombreAmigable").value("Árbitro"))
                .andExpect(jsonPath("$.content[0].esSistema").value(false))
                .andExpect(jsonPath("$.content[0].eliminado").value(false))
                .andExpect(jsonPath("$.content[0].cantidadUsuarios").value(3))
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.size").value(5))
                .andExpect(jsonPath("$.page.totalElements").value(6))
                .andExpect(jsonPath("$.page.totalPages").value(2));

        ArgumentCaptor<FiltroRoles> filtro = ArgumentCaptor.forClass(FiltroRoles.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(rolService).listar(filtro.capture(), pageable.capture());
        assertThat(filtro.getValue()).isEqualTo(new FiltroRoles("arbi", EstadoGeneral.ACTIVO, true));
        assertThat(pageable.getValue()).isEqualTo(PageRequest.of(1, 5, Sort.by(Sort.Direction.DESC, "nombre")));
    }

    @Test
    @DisplayName("GET /roles sin parámetros: página 0 de 20 por id; más de 100 por página no se da")
    @WithMockUser(authorities = "ROL_VER")
    void listarSinParametrosUsaLosDefaultsDelContrato() throws Exception {
        when(rolService.listar(any(), any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get(ROLES)).andExpect(status().isOk());
        mockMvc.perform(get(ROLES).param("size", "500")).andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(rolService, times(2)).listar(eq(new FiltroRoles(null, null, null)), pageable.capture());
        assertThat(pageable.getAllValues()).extracting(Pageable::getPageSize).containsExactly(20, 100);
        assertThat(pageable.getAllValues().getFirst().getSort()).isEqualTo(Sort.by("id"));
    }

    @Test
    @DisplayName("GET /roles con un estado que no existe o un q largo: 400 VALIDACION con el campo")
    @WithMockUser(authorities = "ROL_VER")
    void filtrosInvalidosDan400() throws Exception {
        mockMvc.perform(get(ROLES).param("estado", "BORRADO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores[0].campo").value("estado"));

        mockMvc.perform(get(ROLES).param("q", "x".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores[0].campo").value("q"))
                .andExpect(jsonPath("$.errores[0].codigo").value("LONGITUD"));

        verifyNoInteractions(rolService);
    }

    // --- detalle ------------------------------------------------------------

    @Test
    @DisplayName("GET /roles/{id}: el rol con sus permisos y las fechas en ISO-8601")
    @WithMockUser(authorities = "ROL_VER")
    void verDevuelveElDetalle() throws Exception {
        when(rolService.detalle(2L)).thenReturn(detalle(2L, "ADMINISTRADOR", List.of("USUARIO_VER", "ROL_VER")));

        mockMvc.perform(get(ROLES + "/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("ADMINISTRADOR"))
                .andExpect(jsonPath("$.permisos[0]").value("USUARIO_VER"))
                .andExpect(jsonPath("$.permisos[1]").value("ROL_VER"))
                .andExpect(jsonPath("$.creadoEn").value("2026-09-19T12:00:00Z"))
                .andExpect(jsonPath("$.eliminadoEn").isEmpty());
    }

    // --- alta y modificación --------------------------------------------------

    @Test
    @DisplayName("POST /roles: 201 con Location y el rol creado")
    @WithMockUser(authorities = "ROL_CREAR")
    void crearDevuelve201ConLocation() throws Exception {
        when(rolService.crear(any())).thenReturn(detalle(7L, "VEEDOR", List.of()));

        mockMvc.perform(post(ROLES).contentType(MediaType.APPLICATION_JSON).content(BODY_VEEDOR))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/admin/roles/7"))
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.permisos").isEmpty());

        verify(rolService).crear(new RolRequest("VEEDOR", "Veedor", "Mira", EstadoGeneral.ACTIVO));
    }

    @Test
    @DisplayName("POST /roles con nombre en minúscula y sin nombre amigable: 400 con los dos campos")
    @WithMockUser(authorities = "ROL_CREAR")
    void crearConBodyInvalidoDa400() throws Exception {
        mockMvc.perform(post(ROLES).contentType(MediaType.APPLICATION_JSON).content("{\"nombre\": \"veedor\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores[?(@.campo=='nombre')].codigo").value("FORMATO_INVALIDO"))
                .andExpect(jsonPath("$.errores[?(@.campo=='nombreAmigable')].codigo").value("REQUERIDO"));

        verifyNoInteractions(rolService);
    }

    @Test
    @DisplayName("PUT /roles/{id}: 200 con el rol modificado")
    @WithMockUser(authorities = "ROL_EDITAR")
    void modificarDevuelveElRol() throws Exception {
        when(rolService.modificar(eq(7L), any())).thenReturn(detalle(7L, "VEEDOR", List.of()));

        mockMvc.perform(put(ROLES + "/7").contentType(MediaType.APPLICATION_JSON).content(BODY_VEEDOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("VEEDOR"));
    }

    @Test
    @DisplayName("PUT /roles/{id} sobre un rol protegido: 403 ROL_PROTEGIDO, no SIN_PERMISO")
    @WithMockUser(authorities = "ROL_EDITAR")
    void modificarUnRolProtegidoDa403ConSuCodigo() throws Exception {
        when(rolService.modificar(eq(1L), any()))
                .thenThrow(new ProhibidoException(CodigoError.ROL_PROTEGIDO, "es del sistema"));

        mockMvc.perform(put(ROLES + "/1").contentType(MediaType.APPLICATION_JSON).content(BODY_VEEDOR))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("ROL_PROTEGIDO"));
    }

    // --- baja y reactivación ------------------------------------------------

    @Test
    @DisplayName("DELETE /roles/{id}: 204 sin cuerpo")
    @WithMockUser(authorities = "ROL_ELIMINAR")
    void eliminarDevuelve204() throws Exception {
        mockMvc.perform(delete(ROLES + "/7"))
                .andExpect(status().isNoContent());

        verify(rolService).eliminar(7L);
    }

    @Test
    @DisplayName("POST /roles/{id}/reactivar: 200 con el rol")
    @WithMockUser(authorities = "ROL_ELIMINAR")
    void reactivarDevuelveElRol() throws Exception {
        when(rolService.reactivar(7L)).thenReturn(detalle(7L, "VEEDOR", List.of()));

        mockMvc.perform(post(ROLES + "/7/reactivar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eliminado").value(false));
    }

    // --- permisos de un rol (PICA-126) ---------------------------------------

    @Test
    @DisplayName("PUT /roles/{id}/permisos: pasa la lista al servicio y devuelve el rol")
    @WithMockUser(authorities = "ROL_EDITAR")
    void reemplazarPermisosDevuelveElRol() throws Exception {
        when(rolService.reemplazarPermisos(eq(5L), any()))
                .thenReturn(detalle(5L, "SOPORTE", List.of("USUARIO_VER", "PERSONA_VER")));

        mockMvc.perform(put(ROLES + "/5/permisos").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permisos\": [\"PERSONA_VER\", \"USUARIO_VER\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permisos[0]").value("USUARIO_VER"));

        verify(rolService).reemplazarPermisos(5L, List.of("PERSONA_VER", "USUARIO_VER"));
    }

    @Test
    @DisplayName("PUT /roles/{id}/permisos sin la lista o con un código vacío: 400")
    @WithMockUser(authorities = "ROL_EDITAR")
    void reemplazarPermisosSinListaDa400() throws Exception {
        mockMvc.perform(put(ROLES + "/5/permisos").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores[0].campo").value("permisos"))
                .andExpect(jsonPath("$.errores[0].codigo").value("REQUERIDO"));

        mockMvc.perform(put(ROLES + "/5/permisos").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permisos\": [\"\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"));

        verifyNoInteractions(rolService);
    }

    // --- permisos -----------------------------------------------------------

    @Test
    @DisplayName("El Administrador del seed ve los roles pero no los crea, modifica, da de baja ni les cambia permisos")
    // los permisos que V4 le da al ADMINISTRADOR
    @WithMockUser(authorities = {
            "USUARIO_VER", "USUARIO_CREAR", "USUARIO_EDITAR", "USUARIO_ELIMINAR",
            "PERSONA_VER", "PERSONA_CREAR", "PERSONA_EDITAR", "PERSONA_ELIMINAR",
            "ROL_VER", "ROL_ASIGNAR"})
    void administradorSoloVeLosRoles() throws Exception {
        when(rolService.listar(any(), any())).thenReturn(new PageImpl<>(List.of()));
        when(rolService.detalle(2L)).thenReturn(detalle(2L, "ADMINISTRADOR", List.of()));

        mockMvc.perform(get(ROLES)).andExpect(status().isOk());
        mockMvc.perform(get(ROLES + "/2")).andExpect(status().isOk());

        mockMvc.perform(post(ROLES).contentType(MediaType.APPLICATION_JSON).content(BODY_VEEDOR))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
        mockMvc.perform(put(ROLES + "/2").contentType(MediaType.APPLICATION_JSON).content(BODY_VEEDOR))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(ROLES + "/2")).andExpect(status().isForbidden());
        mockMvc.perform(post(ROLES + "/2/reactivar")).andExpect(status().isForbidden());
        mockMvc.perform(put(ROLES + "/2/permisos").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permisos\": []}"))
                .andExpect(status().isForbidden());

        verify(rolService).listar(any(), any());
        verify(rolService).detalle(2L);
        verifyNoMoreInteractions(rolService);
    }

    @Test
    @DisplayName("Sin ROL_VER no se ven los roles")
    @WithMockUser(authorities = {"USUARIO_VER", "PERSONA_VER"})
    void sinRolVerDa403() throws Exception {
        mockMvc.perform(get(ROLES))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
        mockMvc.perform(get(ROLES + "/2")).andExpect(status().isForbidden());

        verifyNoInteractions(rolService);
    }

    private static RolDetalle detalle(Long id, String nombre, List<String> permisos) {
        return new RolDetalle(id, nombre, nombre, null, EstadoGeneral.ACTIVO, false, false, 0, permisos,
                null, Instant.parse("2026-09-19T12:00:00Z"), Instant.parse("2026-09-19T12:00:00Z"));
    }
}
