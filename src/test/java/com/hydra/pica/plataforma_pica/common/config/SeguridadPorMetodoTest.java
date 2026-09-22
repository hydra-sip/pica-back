package com.hydra.pica.plataforma_pica.common.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DoD de PICA-124: quien no tiene el permiso recibe 403 en /admin/**, quien lo tiene 200.
 * Usa un controller de prueba porque los de verdad los va escribiendo cada uno en su subtarea;
 * lo que se prueba acá es que @EnableMethodSecurity está activo y que el permiso se lee de las
 * authorities, que es donde el filtro JWT (PICA-117) va a poner los códigos del token.
 */
@WebMvcTest(controllers = SeguridadPorMetodoTest.ControllerDePrueba.class)
@Import({SecurityConfig.class, WebConfig.class, SeguridadPorMetodoTest.ControllerDePrueba.class})
class SeguridadPorMetodoTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Con el permiso: 200")
    @WithMockUser(authorities = "USUARIO_VER")
    void conElPermisoPasa() throws Exception {
        mockMvc.perform(get("/api/v1/admin/prueba")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Autenticado pero con otros permisos (un Participante): 403")
    @WithMockUser(authorities = {"PERSONA_VER", "ROL_VER"})
    void sinElPermisoDa403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/prueba")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Un rol no es un permiso: ROLE_ADMINISTRADOR no alcanza")
    @WithMockUser(roles = "ADMINISTRADOR")
    void elRolNoReemplazaAlPermiso() throws Exception {
        mockMvc.perform(get("/api/v1/admin/prueba")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Sin autenticar: 401, ni llega al chequeo de permiso")
    void sinAutenticarDa401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/prueba")).andExpect(status().isUnauthorized());
    }

    @RestController
    static class ControllerDePrueba {

        @GetMapping("/api/v1/admin/prueba")
        @PreAuthorize("hasAuthority('USUARIO_VER')")
        String protegido() {
            return "ok";
        }
    }
}
