package com.hydra.pica.plataforma_pica.user.controller;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import com.hydra.pica.plataforma_pica.user.service.NuevoUsuario;
import com.hydra.pica.plataforma_pica.user.service.UsuarioService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * HU-701 (PICA-412): las tres pruebas de aceptación del acceso al admin, con login de verdad y los permisos del seed.
 * Un usuario general no entra a ninguna pantalla del admin; el administrador sí; soporte entra a lo suyo
 * (reclamos, cancelaciones, bloqueos y auditoría) y no a la configuración ni a la parte económica.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class AccesoAdminIntegracionTest {

    private static final String CLAVE = "Pica2026";
    private static final AtomicInteger SECUENCIA = new AtomicInteger();

    private static final List<String> PANTALLAS_ADMIN = List.of(
            "/api/v1/admin/usuarios",
            "/api/v1/admin/personas",
            "/api/v1/admin/roles",
            "/api/v1/admin/permisos",
            "/api/v1/admin/parametros",
            "/api/v1/admin/cancelaciones",
            "/api/v1/admin/reclamos",
            "/api/v1/admin/auditoria",
            "/api/v1/admin/conciliacion");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UsuarioService usuarioService;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("Un usuario general entra con su usuario y contraseña pero el admin le responde 403 en todo")
    void usuarioGeneralNoEntraAlAdmin() throws Exception {
        String token = login(usuarioCon("PARTICIPANTE"));

        me(token).andExpect(jsonPath("$.permisos", not(hasItem("USUARIO_VER"))));
        for (String ruta : PANTALLAS_ADMIN) {
            sinPermiso(get(ruta), token);
        }
    }

    @Test
    @DisplayName("Un administrador entra al admin y ve todas las pantallas")
    void administradorEntraAlAdmin() throws Exception {
        String token = login(usuarioCon("ADMINISTRADOR"));

        me(token).andExpect(jsonPath("$.roles[*].nombre", hasItem("ADMINISTRADOR")));
        for (String ruta : PANTALLAS_ADMIN.subList(0, PANTALLAS_ADMIN.size() - 1)) {
            pedir(get(ruta), token).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("Soporte entra al admin a reclamos, cancelaciones, usuarios y auditoría, pero no a la configuración")
    void soporteEntraSoloASusPantallas() throws Exception {
        String token = login(usuarioCon("SOPORTE"));

        me(token)
                .andExpect(jsonPath("$.roles[*].nombre", hasItem("SOPORTE")))
                .andExpect(jsonPath("$.permisos", hasItems(
                        "CANCELACION_REVISAR", "RECLAMO_RESOLVER", "AUDITORIA_VER", "USUARIO_VER", "USUARIO_BLOQUEAR")));
        for (String ruta : List.of("/api/v1/admin/usuarios", "/api/v1/admin/cancelaciones",
                "/api/v1/admin/reclamos", "/api/v1/admin/auditoria")) {
            pedir(get(ruta), token).andExpect(status().isOk());
        }
        for (String ruta : List.of("/api/v1/admin/parametros", "/api/v1/admin/conciliacion",
                "/api/v1/admin/roles", "/api/v1/admin/personas")) {
            sinPermiso(get(ruta), token);
        }
    }

    @Test
    @DisplayName("Soporte bloquea a un usuario pero no lo edita ni le resetea la contraseña")
    void soporteBloqueaPeroNoEdita() throws Exception {
        String token = login(usuarioCon("SOPORTE"));
        Long id = idDe(usuarioCon("PARTICIPANTE"));

        sinPermiso(put("/api/v1/admin/usuarios/" + id + "/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("password", "Otra2026"))), token);
        pedir(post("/api/v1/admin/usuarios/" + id + "/bloqueo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("motivo", "Insultos en el chat"))), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("BLOQUEADO"));
    }

    private String usuarioCon(String rol) {
        String username = "acceso" + SECUENCIA.incrementAndGet();
        Persona persona = new Persona();
        persona.setNombres("Acceso");
        persona.setApellidos(rol);
        persona.setEstado(EstadoGeneral.ACTIVO);
        persona = personaRepository.save(persona);
        usuarioService.crear(NuevoUsuario.porAdmin(username, username + "@example.com", CLAVE, null, null,
                persona.getId(), Set.of()));
        // directo en la base: por el servicio haría falta alguien logueado con permiso para asignarlo
        jdbc.update("INSERT INTO usuario_rol (usuario_id, rol_id, asignado_por) SELECT ?, id, 'SISTEMA' FROM rol WHERE nombre = ?",
                idDe(username), rol);
        return username;
    }

    private Long idDe(String username) {
        return usuarioRepository.findByUsernameIgnoreCase(username).orElseThrow().getId();
    }

    private String login(String username) throws Exception {
        String respuesta = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("identificador", username, "password", CLAVE))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(respuesta).get("accessToken").asText();
    }

    private ResultActions me(String token) throws Exception {
        return pedir(get("/api/v1/me"), token).andExpect(status().isOk());
    }

    private ResultActions pedir(MockHttpServletRequestBuilder pedido, String token) throws Exception {
        return mockMvc.perform(pedido.header("Authorization", "Bearer " + token));
    }

    private void sinPermiso(MockHttpServletRequestBuilder pedido, String token) throws Exception {
        pedir(pedido, token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
    }
}
