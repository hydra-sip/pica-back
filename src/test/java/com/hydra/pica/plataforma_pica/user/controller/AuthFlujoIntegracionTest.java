package com.hydra.pica.plataforma_pica.user.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.event.RolesDeUsuarioCambiados;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import com.hydra.pica.plataforma_pica.user.service.NuevoUsuario;
import com.hydra.pica.plataforma_pica.user.service.UsuarioEdicionService;
import com.hydra.pica.plataforma_pica.user.service.UsuarioService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Login, refresh y cierre de sesiones de punta a punta: HTTP, filtro JWT y Postgres. Sin
 * {@code @Transactional} a propósito: lo que se prueba es qué queda guardado cuando el refresh
 * responde 401, y dentro de la transacción del test eso no se ve. Cada test usa su propio usuario.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class AuthFlujoIntegracionTest {

    private static final String CLAVE = "Pica2026";
    private static final AtomicInteger SECUENCIA = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UsuarioService usuarioService;
    @Autowired private UsuarioEdicionService usuarioEdicionService;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationEventPublisher eventos;
    @Autowired private TransactionTemplate transaccion;

    @Test
    void reutilizarUnRefreshYaRotadoCierraTodaLaSesion() throws Exception {
        String username = usuarioActivo();
        JsonNode primero = login(username);
        JsonNode segundo = renovar(primero);

        refreshRechazado(primero, "REFRESH_REUTILIZADO");
        // la revocación de la familia quedó guardada aunque la respuesta fue un 401
        refreshRechazado(segundo, "REFRESH_INVALIDO");
    }

    @Test
    void conElAccessDelLoginElMeDevuelveAlUsuario() throws Exception {
        String username = usuarioActivo();
        String access = login(username).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario.username").value(username));
    }

    @Test
    void darDeBajaAlUsuarioCierraSusSesiones() throws Exception {
        String username = usuarioActivo();
        JsonNode par = login(username);

        usuarioEdicionService.eliminar(idDe(username));

        refreshRechazado(par, "REFRESH_INVALIDO");
    }

    @Test
    void cambiarleLosRolesCierraSusSesiones() throws Exception {
        String username = usuarioActivo();
        JsonNode par = login(username);
        Long id = idDe(username);

        transaccion.executeWithoutResult(estado -> eventos.publishEvent(new RolesDeUsuarioCambiados(id)));

        refreshRechazado(par, "REFRESH_INVALIDO");
    }

    @Test
    void unUsuarioQueDejoDeEstarActivoNoRenuevaAunqueSuTokenSigaVivo() throws Exception {
        // tocando la base directo no hay evento que revoque: lo frena el chequeo del refresh
        String bloqueado = usuarioActivo();
        JsonNode parBloqueado = login(bloqueado);
        jdbc.update("UPDATE usuario SET estado = 'BLOQUEADO' WHERE username = ?", bloqueado);

        String eliminado = usuarioActivo();
        JsonNode parEliminado = login(eliminado);
        jdbc.update("UPDATE usuario SET eliminado_en = now() WHERE username = ?", eliminado);

        refreshRechazado(parBloqueado, "REFRESH_INVALIDO");
        refreshRechazado(parEliminado, "REFRESH_INVALIDO");
    }

    @Test
    void unRefreshVencidoEsInvalido() throws Exception {
        String username = usuarioActivo();
        JsonNode par = login(username);
        jdbc.update("UPDATE refresh_token SET expira_en = now() - interval '1 minute' WHERE usuario_id = ?",
                idDe(username));

        refreshRechazado(par, "REFRESH_INVALIDO");
    }

    private String usuarioActivo() {
        String username = "flujo" + SECUENCIA.incrementAndGet();
        Persona persona = new Persona();
        persona.setNombres("Flujo");
        persona.setApellidos("Prueba");
        persona.setEstado(EstadoGeneral.ACTIVO);
        persona = personaRepository.save(persona);
        usuarioService.crear(NuevoUsuario.porAdmin(username, username + "@example.com", CLAVE, null, null,
                persona.getId(), Set.of()));
        return username;
    }

    private Long idDe(String username) {
        return usuarioRepository.findByUsernameIgnoreCase(username).orElseThrow().getId();
    }

    private JsonNode login(String username) throws Exception {
        String respuesta = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("identificador", username, "password", CLAVE))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(respuesta);
    }

    private JsonNode renovar(JsonNode par) throws Exception {
        String respuesta = pedirRefresh(par).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(respuesta);
    }

    private void refreshRechazado(JsonNode par, String codigo) throws Exception {
        pedirRefresh(par)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value(codigo));
    }

    private ResultActions pedirRefresh(JsonNode par) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", par.get("refreshToken").asText()))));
    }
}
