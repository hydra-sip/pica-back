package com.hydra.pica.plataforma_pica.reputacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hydra.pica.plataforma_pica.EscenarioTorneo;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.partido.dto.ResultadoRequest;
import com.hydra.pica.plataforma_pica.partido.service.ResultadoService;
import com.hydra.pica.plataforma_pica.user.service.UsuarioEdicionService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * Las pruebas de aceptación del perfil público del jugador (HU-801, T801-2, PICA-419): el escenario se arma con los
 * servicios de la aplicación y el perfil se pide por HTTP, sin sesión. Solo se mira el JSON.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, EscenarioTorneo.class})
@Transactional
class PerfilJugadorIntegracionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private EscenarioTorneo escenario;
    @Autowired private UsuarioEdicionService usuarioEdicionService;
    @Autowired private ResultadoService resultadoService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EntityManager entityManager;

    private Long organizador;
    private Long arbitro;

    @BeforeEach
    void preparar() {
        organizador = escenario.usuario("org_perfil_jugador", "ORGANIZADOR").getId();
        arbitro = escenario.usuario("juez_perfil_jugador", "ARBITRO").getId();
    }

    @Test
    void sinSesionElPerfilDeUnJugadorMuestraSuIdentidadVisible() throws Exception {
        Long jugador = escenario.usuario("jugador_perfil", "PARTICIPANTE").getId();

        perfil(jugador)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(jugador))
                .andExpect(jsonPath("$.username").value("jugador_perfil"))
                .andExpect(jsonPath("$.nombreCompleto").value("jugador_perfil Test"))
                .andExpect(jsonPath("$.avatar").isEmpty());
    }

    @Test
    void unUsuarioQueNoExisteDa404() throws Exception {
        perfil(Long.MAX_VALUE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("USUARIO_NO_ENCONTRADO"));
    }

    @Test
    void unUsuarioDadoDeBajaDa404() throws Exception {
        Long jugador = escenario.usuario("jugador_baja", "PARTICIPANTE").getId();

        usuarioEdicionService.eliminar(jugador);

        perfil(jugador)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("USUARIO_NO_ENCONTRADO"));
    }

    @Test
    void unUsuarioBloqueadoSeMuestraIgual() throws Exception {
        Long jugador = escenario.usuario("jugador_bloqueado", "PARTICIPANTE").getId();

        usuarioEdicionService.bloquear(jugador, "Insultos en el chat", null);

        perfil(jugador)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("jugador_bloqueado"));
    }

    @Test
    void unJugadorNuevoTieneElHistorialEnCero() throws Exception {
        Long nuevo = escenario.usuario("jugador_nuevo", "PARTICIPANTE").getId();

        perfil(nuevo)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.historial.torneosJugados").value(0))
                .andExpect(jsonPath("$.historial.victorias").value(0))
                .andExpect(jsonPath("$.historial.derrotas").value(0))
                .andExpect(jsonPath("$.historial.podios").value(0));
    }

    @Test
    void despuesDeJugarYFinalizarUnTorneoElHistorialLoRefleja() throws Exception {
        Long torneo = escenario.individualEnCurso(organizador, arbitro, "Copa del perfil", "jugador_final_", 2);
        Long laFinal = escenario.partido(torneo, 1, 1);
        Long campeon = escenario.jugador(laFinal, "LOCAL");
        Long subcampeon = escenario.jugador(laFinal, "VISITANTE");

        jugar(laFinal, 2, 0);

        perfil(campeon)
                .andExpect(jsonPath("$.historial.torneosJugados").value(1))
                .andExpect(jsonPath("$.historial.victorias").value(1))
                .andExpect(jsonPath("$.historial.derrotas").value(0))
                .andExpect(jsonPath("$.historial.podios").value(1));
        perfil(subcampeon)
                .andExpect(jsonPath("$.historial.torneosJugados").value(1))
                .andExpect(jsonPath("$.historial.victorias").value(0))
                .andExpect(jsonPath("$.historial.derrotas").value(1))
                .andExpect(jsonPath("$.historial.podios").value(1));
    }

    /** Un campo privado agregado por error (email, documento, datos bancarios) rompe este test. */
    @Test
    void elPerfilTraeSoloLasClavesPublicas() throws Exception {
        Long jugador = escenario.usuario("jugador_claves", "PARTICIPANTE").getId();

        JsonNode perfil = json(jugador);

        assertThat(claves(perfil)).containsExactlyInAnyOrder(
                "id", "username", "nombreCompleto", "avatar", "historial");
        assertThat(claves(perfil.get("historial"))).containsExactlyInAnyOrder(
                "torneosJugados", "victorias", "derrotas", "podios");
    }

    // --- helpers --------------------------------------------------------------

    private ResultActions perfil(Long usuarioId) throws Exception {
        return mockMvc.perform(get("/api/v1/usuarios/{id}/perfil", usuarioId));
    }

    private JsonNode json(Long usuarioId) throws Exception {
        String cuerpo = perfil(usuarioId).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(cuerpo);
    }

    private static List<String> claves(JsonNode objeto) {
        List<String> claves = new ArrayList<>();
        objeto.fieldNames().forEachRemaining(claves::add);
        return claves;
    }

    /** Carga el resultado desde el local y lo acepta el visitante. */
    private void jugar(Long partidoId, int golesLocal, int golesVisitante) {
        resultadoService.cargar(escenario.jugador(partidoId, "LOCAL"), partidoId,
                new ResultadoRequest(golesLocal, golesVisitante, null, null));
        resultadoService.aceptar(escenario.jugador(partidoId, "VISITANTE"), partidoId);
        entityManager.flush();
    }
}
