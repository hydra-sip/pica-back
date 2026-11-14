package com.hydra.pica.plataforma_pica.discord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.hydra.pica.plataforma_pica.EscenarioTorneo;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoInformacion;
import com.hydra.pica.plataforma_pica.torneo.event.WebhookDiscordGuardado;
import com.hydra.pica.plataforma_pica.torneo.service.DiscordTorneoService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
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
 * El webhook de Discord del torneo (PICA-370) contra Postgres, con Discord simulado: guardarlo, quitarlo, quién y
 * cuándo, y el mensaje de prueba. El aviso se llama a mano con el evento, porque el test no confirma su transacción.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, EscenarioTorneo.class})
@Transactional
@RecordApplicationEvents
class DiscordTorneoIntegracionTest {

    static final String WEBHOOK = "https://discord.com/api/webhooks/1234567890/aBc-DeF_ghij";
    static final String OTRO_WEBHOOK = "https://discord.com/api/webhooks/987/otroToken_wxyz";

    private static final RestClient.Builder BUILDER = RestClient.builder();
    private static final MockRestServiceServer DISCORD = MockRestServiceServer.bindTo(BUILDER).build();

    @TestBean(name = "discordHttp", methodName = "discordFalso")
    private RestClient discordHttp;

    static RestClient discordFalso() {
        return BUILDER.build();
    }

    @Autowired private DiscordTorneoService discordTorneoService;
    @Autowired private AvisosDiscord avisos;
    @Autowired private EscenarioTorneo escenario;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private ApplicationEvents eventos;
    @MockitoBean private EmailService emailService;

    private Long organizador;
    private Long torneo;

    @BeforeEach
    void enCurso() {
        DISCORD.reset();
        organizador = escenario.usuario("org_discord", "ORGANIZADOR").getId();
        Long arbitro = escenario.usuario("juez_discord", "ARBITRO").getId();
        torneo = escenario.individualEnCurso(organizador, arbitro, "Copa Discord", "jugador_discord_", 4);
    }

    @AfterEach
    void todoLoEsperadoLlego() {
        DISCORD.verify();
    }

    @Test
    void guardarloLoDevuelveEnmascaradoYMandaElMensajeDePrueba() {
        TorneoInformacion informacion = discordTorneoService.guardar(organizador, torneo, WEBHOOK);

        assertThat(informacion.discordWebhook()).isEqualTo("https://discord.com/api/webhooks/…/••••ghij");
        assertThat(webhookGuardado()).isEqualTo(WEBHOOK);
        DISCORD.expect(requestTo(WEBHOOK))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("PICA quedó conectado a este canal"),
                        org.hamcrest.Matchers.containsString("Copa Discord"))))
                .andExpect(jsonPath("$.allowed_mentions.parse").isEmpty())
                .andRespond(withSuccess());
        avisarPrueba();
    }

    @Test
    void guardarElMismoOtraVezVuelveAMandarLaPrueba() {
        discordTorneoService.guardar(organizador, torneo, WEBHOOK);
        discordTorneoService.guardar(organizador, torneo, WEBHOOK);

        assertThat(eventos.stream(WebhookDiscordGuardado.class)).hasSize(2);
    }

    @Test
    void cambiarloPorOtroPublicaEnElNuevo() {
        discordTorneoService.guardar(organizador, torneo, WEBHOOK);
        TorneoInformacion informacion = discordTorneoService.guardar(organizador, torneo, OTRO_WEBHOOK);

        assertThat(informacion.discordWebhook()).endsWith("••••wxyz");
        DISCORD.expect(requestTo(OTRO_WEBHOOK)).andRespond(withSuccess());
        avisarPrueba();
    }

    @Test
    void siLaPruebaFallaElWebhookQuedaGuardado() {
        discordTorneoService.guardar(organizador, torneo, WEBHOOK);
        DISCORD.expect(requestTo(WEBHOOK)).andRespond(withServerError());

        avisarPrueba();

        assertThat(webhookGuardado()).isEqualTo(WEBHOOK);
    }

    @Test
    void quitarloLoBorraYNoSePublicaMas() {
        discordTorneoService.guardar(organizador, torneo, WEBHOOK);

        TorneoInformacion informacion = discordTorneoService.quitar(organizador, torneo);

        assertThat(informacion.discordWebhook()).isNull();
        assertThat(webhookGuardado()).isNull();
        avisarPrueba();   // DISCORD no espera nada: verify falla si llega algo
    }

    @Test
    void sinWebhookElTorneoNoLoTrae() {
        assertThat(jdbc.queryForObject("SELECT discord_webhook FROM torneo WHERE id = ?", String.class, torneo))
                .isNull();
        assertThat(discordTorneoService.quitar(organizador, torneo).discordWebhook()).isNull();
    }

    @Test
    void elCambioQuedaEnLaAuditoriaSinLaUrl() {
        discordTorneoService.guardar(organizador, torneo, WEBHOOK);
        discordTorneoService.quitar(organizador, torneo);

        assertThat(jdbc.queryForList("""
                SELECT detalle->>'webhook' FROM auditoria
                WHERE entidad = 'TORNEO' AND entidad_id = ? AND detalle->>'cambio' = 'DISCORD' ORDER BY id""",
                String.class, String.valueOf(torneo))).containsExactly("GUARDADO", "QUITADO");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auditoria WHERE detalle::text LIKE '%webhooks%'",
                Integer.class)).isZero();
    }

    @Test
    void otroUsuarioNoLoEncuentra() {
        Long otro = escenario.usuario("otro_org_discord", "ORGANIZADOR").getId();

        assertThatThrownBy(() -> discordTorneoService.guardar(otro, torneo, WEBHOOK))
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_ENCONTRADO);
        assertThatThrownBy(() -> discordTorneoService.quitar(otro, torneo))
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_ENCONTRADO);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FINALIZADO", "CANCELADO"})
    void terminadoNoSeConfigura(String estado) {
        jdbc.update("UPDATE torneo SET estado = ?, motivo_cancelacion = 'Lluvia' WHERE id = ?", estado, torneo);
        entityManager.clear();

        assertThatThrownBy(() -> discordTorneoService.guardar(organizador, torneo, WEBHOOK))
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_EDITABLE);
        assertThatThrownBy(() -> discordTorneoService.quitar(organizador, torneo))
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_EDITABLE);
    }

    @Test
    void enBorradorSeConfigura() {
        jdbc.update("UPDATE torneo SET estado = 'BORRADOR' WHERE id = ?", torneo);
        entityManager.clear();

        assertThat(discordTorneoService.guardar(organizador, torneo, WEBHOOK).discordWebhook()).isNotNull();
    }

    private void avisarPrueba() {
        entityManager.flush();
        entityManager.clear();
        avisos.avisar(new WebhookDiscordGuardado(torneo));
    }

    private String webhookGuardado() {
        entityManager.flush();
        return jdbc.queryForObject("SELECT discord_webhook FROM torneo WHERE id = ?", String.class, torneo);
    }
}
