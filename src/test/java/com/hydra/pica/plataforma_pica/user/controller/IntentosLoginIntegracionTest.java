package com.hydra.pica.plataforma_pica.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.service.NuevoUsuario;
import com.hydra.pica.plataforma_pica.user.service.UsuarioService;
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

/**
 * Límite de logins fallidos (RNF-SEG-06) de punta a punta. Sin {@code @Transactional}: el fallido se guarda en su
 * propia transacción y la auditoría no se borra, así que cada test usa su propio usuario.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class IntentosLoginIntegracionTest {

    private static final String CLAVE = "Pica2026";
    private static final AtomicInteger SECUENCIA = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UsuarioService usuarioService;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void conCincoFallidosElSextoIntentoDa429AunqueLaClaveSeaCorrecta() throws Exception {
        String username = usuarioActivo();
        for (int i = 0; i < 5; i++) {
            login(username, "incorrecta").andExpect(status().isUnauthorized());
        }

        login(username, CLAVE)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.codigo").value("DEMASIADOS_INTENTOS"))
                // hasta que el primer fallido sale de la ventana de 15 minutos
                .andExpect(r -> assertThat(Integer.parseInt(r.getResponse().getHeader("Retry-After")))
                        .isBetween(840, 900))
                .andExpect(r -> assertThat(objectMapper.readTree(r.getResponse().getContentAsString())
                        .get("reintentarEnSegundos").asInt()).isBetween(840, 900));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auditoria a JOIN usuario u ON u.id = a.usuario_id
                WHERE a.accion = 'LOGIN_FALLIDO' AND u.username = ? AND a.ip IS NOT NULL""", Integer.class, username))
                .isEqualTo(5);
    }

    @Test
    void cuentaPorIdentificadorSinMayusculasAunqueNoExista() throws Exception {
        String inexistente = "nadie" + SECUENCIA.incrementAndGet();
        for (String escrito : new String[] {inexistente, inexistente.toUpperCase(), " " + inexistente, inexistente,
                inexistente}) {
            login(escrito, "cualquiera").andExpect(status().isUnauthorized());
        }

        login(inexistente, "cualquiera").andExpect(status().isTooManyRequests());
    }

    @Test
    void unLoginCorrectoReiniciaLaCuenta() throws Exception {
        String username = usuarioActivo();
        for (int i = 0; i < 4; i++) {
            login(username, "incorrecta").andExpect(status().isUnauthorized());
        }
        login(username, CLAVE).andExpect(status().isOk());
        for (int i = 0; i < 4; i++) {
            login(username, "incorrecta").andExpect(status().isUnauthorized());
        }

        login(username, CLAVE).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auditoria a JOIN usuario u ON u.id = a.usuario_id
                WHERE a.accion = 'LOGIN' AND u.username = ?""", Integer.class, username)).isEqualTo(2);
    }

    private ResultActions login(String identificador, String clave) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("identificador", identificador, "password", clave))));
    }

    private String usuarioActivo() {
        String username = "intentos" + SECUENCIA.incrementAndGet();
        Persona persona = new Persona();
        persona.setNombres("Intentos");
        persona.setApellidos("Prueba");
        persona.setEstado(EstadoGeneral.ACTIVO);
        persona = personaRepository.save(persona);
        usuarioService.crear(NuevoUsuario.porAdmin(username, username + "@example.com", CLAVE, null, null,
                persona.getId(), Set.of()));
        return username;
    }
}
