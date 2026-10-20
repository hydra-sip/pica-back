package com.hydra.pica.plataforma_pica.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.service.NuevoUsuario;
import com.hydra.pica.plataforma_pica.user.service.UsuarioService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * CE2-8: las duraciones de la sesión salen de la configuración. Con valores cortos se ve, contra
 * Postgres y sin tocar código, que una sesión sin uso vence por inactividad y que una usada todo el
 * tiempo igual se corta al llegar al máximo desde el login.
 *
 * Usa el reloj real, así que los tiempos dejan 1,5 s de margen en cada paso para lo que tardan los
 * requests en un CI cargado. A futuro conviene inyectar un Clock en AuthService y adelantarlo desde el
 * test: sin esperas ni márgenes.
 */
@SpringBootTest(properties = {
        "app.auth.access-ttl=1m",
        "app.auth.refresh-ttl=5s",
        "app.auth.sesion-maxima=10s"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class SesionConfigurableIntegracionTest {

    private static final String CLAVE = "Pica2026";
    private static final AtomicInteger SECUENCIA = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UsuarioService usuarioService;
    @Autowired private PersonaRepository personaRepository;

    @Test
    void elLoginInformaLaVidaDelAccessConfigurada() throws Exception {
        assertThat(login(usuarioActivo()).get("expiresIn").asLong()).isEqualTo(60);
    }

    @Test
    void unaSesionSinUsoVencePorInactividad() throws Exception {
        JsonNode par = login(usuarioActivo());

        Thread.sleep(6_000);

        refresh(par)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("REFRESH_INVALIDO"));
    }

    @Test
    void unaSesionUsadaTodoElTiempoSeCortaAlLlegarAlMaximo() throws Exception {
        JsonNode par = login(usuarioActivo());

        // se renueva cada 3,5 s, antes de los 5 s de inactividad, así que solo la puede cortar el máximo
        // de 10 s: el último refresh llega a los 10,5 s, cuando por inactividad todavía estaría vivo
        Thread.sleep(3_500);
        par = renovar(par);
        Thread.sleep(3_500);
        par = renovar(par);
        Thread.sleep(3_500);

        refresh(par)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("REFRESH_INVALIDO"));
    }

    private String usuarioActivo() {
        String username = "sesion" + SECUENCIA.incrementAndGet();
        Persona persona = new Persona();
        persona.setNombres("Sesion");
        persona.setApellidos("Prueba");
        persona.setEstado(EstadoGeneral.ACTIVO);
        persona = personaRepository.save(persona);
        usuarioService.crear(NuevoUsuario.porAdmin(username, username + "@example.com", CLAVE, null, null,
                persona.getId(), Set.of()));
        return username;
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
        String respuesta = refresh(par).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(respuesta);
    }

    private ResultActions refresh(JsonNode par) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", par.get("refreshToken").asText()))));
    }
}
