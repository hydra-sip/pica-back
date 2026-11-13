package com.hydra.pica.plataforma_pica.discord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import com.hydra.pica.plataforma_pica.EscenarioTorneo;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.partido.event.LlaveSorteada;
import com.hydra.pica.plataforma_pica.partido.service.BracketService;
import jakarta.persistence.EntityManager;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/**
 * Los avisos del torneo al canal de Discord (PICA-371) contra Postgres, con Discord simulado: la llave, los resultados
 * y el check-in. Los avisos se llaman a mano con el evento, porque el test no confirma su transacción; que el evento
 * se publique se mira con {@link ApplicationEvents}.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, EscenarioTorneo.class})
@Transactional
@RecordApplicationEvents
class AvisosDiscordIntegracionTest {

    private static final String WEBHOOK = "https://discord.com/api/webhooks/1234567890/aBc-DeF_ghij";

    private static final RestClient.Builder BUILDER = RestClient.builder();
    private static final MockRestServiceServer DISCORD = MockRestServiceServer.bindTo(BUILDER).build();

    @TestBean(name = "discordHttp", methodName = "discordFalso")
    private RestClient discordHttp;

    static RestClient discordFalso() {
        return BUILDER.build();
    }

    @Autowired private AvisosDiscord avisos;
    @Autowired private BracketService bracketService;
    @Autowired private EscenarioTorneo escenario;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private ApplicationEvents eventos;
    @MockitoBean private EmailService emailService;

    private Long organizador;
    private Long arbitro;
    private Long torneo;

    @BeforeEach
    void enCurso() {
        DISCORD.reset();
        organizador = escenario.usuario("org_avisos", "ORGANIZADOR").getId();
        arbitro = escenario.usuario("juez_avisos", "ARBITRO").getId();
        torneo = escenario.individualEnCurso(organizador, arbitro, "Copa Avisos", "jugador_avisos_", 4);
        conWebhook(torneo);
    }

    @AfterEach
    void todoLoEsperadoLlego() {
        DISCORD.verify();
    }

    // --- la llave ----------------------------------------------------------------

    @Test
    void armarLaLlavePublicaLosCrucesDeLaPrimeraRonda() {
        assertThat(eventos.stream(LlaveSorteada.class)).containsExactly(new LlaveSorteada(torneo, false));
        esperar("🎲 La llave está armada.", "**Semifinal**", cruce(1), cruce(2), "**Copa Avisos**");

        avisos.avisar(new LlaveSorteada(torneo, false));
    }

    @Test
    void regenerarlaPublicaQueSeVolvioASortear() {
        bracketService.regenerar(organizador, torneo);
        refrescar();

        assertThat(eventos.stream(LlaveSorteada.class)).endsWith(new LlaveSorteada(torneo, true));
        esperar("🔁 La llave se volvió a sortear", cruce(1), cruce(2));
        avisos.avisar(new LlaveSorteada(torneo, true));
    }

    @Test
    void conUnByeDiceQuienPasaDirecto() {
        Long conBye = escenario.individualEnCurso(organizador, arbitro, "Copa Impar", "jugador_impar_", 3);
        conWebhook(conBye);

        esperar("pasa directo", " vs ");
        avisos.avisar(new LlaveSorteada(conBye, false));
    }

    @Test
    void sinWebhookNoPublicaLaLlave() {
        jdbc.update("UPDATE torneo SET discord_webhook = NULL WHERE id = ?", torneo);
        refrescar();

        avisos.avisar(new LlaveSorteada(torneo, false));   // DISCORD no espera nada
    }

    @Test
    void losCrucesQueNoEntranSeResumen() {
        List<String> lineas = IntStream.range(0, 100).mapToObj(i -> "• Jugador " + i + " vs Rival " + i).toList();

        String texto = AvisosDiscord.entrando(lineas, 300);

        assertThat(texto.length()).isLessThanOrEqualTo(300);
        assertThat(texto).startsWith("• Jugador 0 vs Rival 0").containsPattern("…y \\d+ cruces más$");
        assertThat(AvisosDiscord.entrando(lineas.subList(0, 2), 300)).doesNotContain("cruces más");
    }

    // --- ayudas -------------------------------------------------------------------

    /** «A vs B» del partido de la primera ronda en esa posición. */
    private String cruce(int posicion) {
        Long partido = escenario.partido(torneo, 1, posicion);
        return nombre(escenario.jugador(partido, "LOCAL")) + " vs " + nombre(escenario.jugador(partido, "VISITANTE"));
    }

    private String nombre(Long usuario) {
        return jdbc.queryForObject("SELECT u.username FROM usuario u WHERE u.id = ?", String.class, usuario) + " Test";
    }

    /** Que el próximo mensaje a Discord tenga todos estos fragmentos. */

    private void esperar(String... fragmentos) {
        @SuppressWarnings("unchecked")
        Matcher<String>[] todos = Arrays.stream(fragmentos).map(f -> containsString(f)).toArray(Matcher[]::new);
        DISCORD.expect(requestTo(WEBHOOK))
                .andExpect(jsonPath("$.content").value(allOf(todos)))
                .andRespond(withSuccess());
    }

    private void conWebhook(Long id) {
        jdbc.update("UPDATE torneo SET discord_webhook = ? WHERE id = ?", WEBHOOK, id);
        refrescar();
    }

    private void refrescar() {
        entityManager.flush();
        entityManager.clear();
    }
}
