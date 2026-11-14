package com.hydra.pica.plataforma_pica.discord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import com.hydra.pica.plataforma_pica.EscenarioTorneo;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.partido.dto.ResultadoRequest;
import com.hydra.pica.plataforma_pica.partido.event.LlaveSorteada;
import com.hydra.pica.plataforma_pica.partido.repository.JuegaRepository;
import com.hydra.pica.plataforma_pica.partido.repository.PartidoRepository;
import com.hydra.pica.plataforma_pica.partido.event.PaseDirecto;
import com.hydra.pica.plataforma_pica.partido.event.ResultadoConfirmado;
import com.hydra.pica.plataforma_pica.partido.service.BracketService;
import com.hydra.pica.plataforma_pica.partido.service.ResultadoService;
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
import org.springframework.transaction.support.TransactionTemplate;
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
    @Autowired private ResultadoService resultadoService;
    @Autowired private AvisoCheckinDiscord avisoCheckin;
    @Autowired private PartidoRepository partidoRepository;
    @Autowired private JuegaRepository juegaRepository;
    @Autowired private CanalDiscord canal;
    @Autowired private TransactionTemplate transaccion;
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

    // --- los resultados ----------------------------------------------------------

    @Test
    void unResultadoPublicaElMarcadorYADondePasaElGanador() {
        Long semi = escenario.partido(torneo, 1, 1);
        String local = nombreDe(semi, "LOCAL");
        String visitante = nombreDe(semi, "VISITANTE");
        confirmar(semi, new ResultadoRequest(3, 1, null, null));

        DISCORD.expect(requestTo(WEBHOOK))
                .andExpect(jsonPath("$.content").value(allOf(containsString("✅ **Semifinal**: **" + local
                        + "** le ganó 3–1 a " + visitante), containsString(local + " pasa a **Final**."),
                        not(containsString("⚔️")), containsString("**Copa Avisos**"))))
                .andRespond(withSuccess());
        avisarResultados();
    }

    @Test
    void conElPartidoSiguienteCompletoSumaElCruceNuevo() {
        Long primera = escenario.partido(torneo, 1, 1);
        Long segunda = escenario.partido(torneo, 1, 2);
        String finalistaUno = nombreDe(primera, "VISITANTE");
        String finalistaDos = nombreDe(segunda, "LOCAL");
        confirmar(primera, new ResultadoRequest(0, 2, null, null));
        eventosDeAntes = eventos.stream(ResultadoConfirmado.class).count();
        confirmar(segunda, new ResultadoRequest(1, 1, 5, 4));

        esperar("le ganó 1–1 (penales 5–4)", "⚔️ **Final**: " + finalistaUno + " vs " + finalistaDos);
        avisarResultados();
    }

    @Test
    void unWalkoverNoTieneMarcador() {
        Long semi = escenario.partido(torneo, 1, 1);
        jdbc.update("UPDATE partido SET estado = 'WALKOVER' WHERE id = ?", semi);
        jdbc.update("UPDATE juega SET es_ganador = (lado = 'LOCAL') WHERE partido_id = ?", semi);
        jdbc.update("""
                INSERT INTO juega (partido_id, inscripcion_id, lado)
                SELECT ?, inscripcion_id, 'LOCAL' FROM juega WHERE partido_id = ? AND lado = 'LOCAL'""",
                escenario.partido(torneo, 2, 1), semi);
        refrescar();

        esperar("**" + nombreDe(semi, "LOCAL") + "** gana por walkover a " + nombreDe(semi, "VISITANTE"));
        avisos.avisar(new ResultadoConfirmado(torneo, semi, null, false));
    }

    @Test
    void laFinalPublicaAlCampeon() {
        Long laFinal = llegarALaFinal();
        String campeon = nombreDe(laFinal, "LOCAL");
        eventosDeAntes = eventos.stream(ResultadoConfirmado.class).count();
        confirmar(laFinal, new ResultadoRequest(4, 2, null, null));

        assertThat(eventos.stream(ResultadoConfirmado.class).skip(eventosDeAntes))
                .singleElement().extracting(ResultadoConfirmado::torneoFinalizado).isEqualTo(true);
        DISCORD.expect(requestTo(WEBHOOK))
                .andExpect(jsonPath("$.content").value(allOf(containsString("✅ **Final**"),
                        containsString("🏆 **" + campeon + "** es campeón del torneo."), not(containsString("pasa a")))))
                .andRespond(withSuccess());
        avisarResultados();
    }

    @Test
    void unPaseDirectoTieneSuAvisoYDespuesElCampeon() {
        Long vacante = escenario.partido(torneo, 1, 1);
        jdbc.update("UPDATE partido SET estado = 'WALKOVER' WHERE id = ?", vacante);
        jdbc.update("UPDATE juega SET es_ganador = false WHERE partido_id = ?", vacante);
        refrescar();
        Long semi = escenario.partido(torneo, 1, 2);
        String finalista = nombreDe(semi, "LOCAL");
        confirmar(semi, new ResultadoRequest(2, 0, null, null));

        assertThat(eventos.stream(PaseDirecto.class)).hasSize(1);
        esperar("✅ **Semifinal**", finalista + " pasa a **Final**.");
        esperar("⏭️ **" + finalista + "** gana **Final** sin jugar: el otro cruce quedó sin ganador.");
        esperar("🏆 **" + finalista + "** es campeón del torneo.");
        List<ResultadoConfirmado> resultados = eventos.stream(ResultadoConfirmado.class).toList();
        avisos.avisar(resultados.get(0));
        avisos.avisar(eventos.stream(PaseDirecto.class).findFirst().orElseThrow());
        avisos.avisar(resultados.get(1));
    }

    @Test
    void siDiscordFallaElResultadoQuedaConfirmado() {
        Long semi = escenario.partido(torneo, 1, 1);
        confirmar(semi, new ResultadoRequest(3, 1, null, null));
        DISCORD.expect(times(CanalDiscord.INTENTOS), requestTo(WEBHOOK)).andRespond(withServerError());

        avisarResultados();

        assertThat(jdbc.queryForObject("SELECT estado FROM partido WHERE id = ?", String.class, semi))
                .isEqualTo("CONFIRMADO");
    }

    @Test
    void sinWebhookNoPublicaResultados() {
        jdbc.update("UPDATE torneo SET discord_webhook = NULL WHERE id = ?", torneo);
        refrescar();
        confirmar(escenario.partido(torneo, 1, 1), new ResultadoRequest(3, 1, null, null));

        avisarResultados();   // DISCORD no espera nada
    }

    // --- el check-in --------------------------------------------------------------

    /** Ahora, sin fracciones: Postgres guarda microsegundos. */
    private final Instant ahora = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @Test
    void avisaLaVentanaQueAcabaDeAbrir() {
        Long semi = conVentana(ahora.plus(29, ChronoUnit.MINUTES));   // abrió hace 1 minuto y cierra en 19
        long cierre = ahora.plus(19, ChronoUnit.MINUTES).getEpochSecond();

        esperar("⏰ Se abrió el check-in de **Semifinal**: " + nombreDe(semi, "LOCAL") + " vs "
                + nombreDe(semi, "VISITANTE"), "Cierra a las <t:" + cierre + ":t> (<t:" + cierre + ":R>).",
                "**Copa Avisos**");
        assertThat(avisoCheckin.avisarAbiertas(ahora)).isEqualTo(1);

        refrescar();
        assertThat(jdbc.queryForObject("SELECT checkin_avisado_para FROM partido WHERE id = ?", Timestamp.class, semi)
                .toInstant()).isEqualTo(ahora.minus(1, ChronoUnit.MINUTES));
    }

    @Test
    void noLaAvisaDosVeces() {
        conVentana(ahora.plus(29, ChronoUnit.MINUTES));
        esperar("⏰ Se abrió el check-in");
        avisoCheckin.avisarAbiertas(ahora);
        refrescar();

        assertThat(avisoCheckin.avisarAbiertas(ahora.plus(30, ChronoUnit.SECONDS))).isZero();
    }

    @Test
    void siSeReprogramaAvisaLaVentanaNueva() {
        Long semi = conVentana(ahora.plus(29, ChronoUnit.MINUTES));
        DISCORD.expect(times(2), requestTo(WEBHOOK)).andRespond(withSuccess());
        avisoCheckin.avisarAbiertas(ahora);
        refrescar();

        jdbc.update("UPDATE partido SET fecha_programada = ? WHERE id = ?",
                Timestamp.from(ahora.plus(1, ChronoUnit.HOURS)), semi);
        Instant despues = ahora.plus(31, ChronoUnit.MINUTES);   // la ventana nueva abrió hace 1 minuto
        refrescar();

        assertThat(avisoCheckin.avisarAbiertas(despues)).isEqualTo(1);
    }

    @Test
    void conLaRevisionCadaQuinceMinutosNoSePierdeUnaVentana() {
        conVentana(ahora.plus(18, ChronoUnit.MINUTES));   // abrió hace 12 minutos: entre dos revisiones
        AvisoCheckinDiscord cadaQuince = new AvisoCheckinDiscord(partidoRepository, juegaRepository, canal, avisos,
                transaccion, Duration.ofMinutes(15), true);

        esperar("⏰ Se abrió el check-in");
        assertThat(cadaQuince.avisarAbiertas(ahora)).isEqualTo(1);
    }

    @Test
    void noAvisaUnaVentanaQueAbrioHaceRato() {
        conVentana(ahora.plus(15, ChronoUnit.MINUTES));   // abrió hace 15 minutos

        assertThat(avisoCheckin.avisarAbiertas(ahora)).isZero();
    }

    @Test
    void noAvisaUnaVentanaQueTodaviaNoAbrio() {
        conVentana(ahora.plus(45, ChronoUnit.MINUTES));

        assertThat(avisoCheckin.avisarAbiertas(ahora)).isZero();
    }

    @Test
    void sinWebhookOSinCheckinConfiguradoNoAvisa() {
        conVentana(ahora.plus(29, ChronoUnit.MINUTES));
        jdbc.update("UPDATE torneo SET discord_webhook = NULL WHERE id = ?", torneo);
        refrescar();
        assertThat(avisoCheckin.avisarAbiertas(ahora)).isZero();

        conWebhook(torneo);
        jdbc.update("UPDATE torneo SET checkin_min_antes = NULL, checkin_duracion_min = NULL WHERE id = ?", torneo);
        refrescar();
        assertThat(avisoCheckin.avisarAbiertas(ahora)).isZero();
    }

    /** La ventana del torneo abre 30 minutos antes y dura 20; la primera semifinal se juega en {@code fecha}. */
    private Long conVentana(Instant fecha) {
        jdbc.update("UPDATE torneo SET checkin_min_antes = 30, checkin_duracion_min = 20 WHERE id = ?", torneo);
        Long semi = escenario.partido(torneo, 1, 1);
        jdbc.update("UPDATE partido SET fecha_programada = ? WHERE id = ?", Timestamp.from(fecha), semi);
        refrescar();
        return semi;
    }

    // --- ayudas -------------------------------------------------------------------

    /** Los ResultadoConfirmado de antes de lo que prueba el test: no se avisan. */
    private long eventosDeAntes;

    /** Carga el resultado el local y lo acepta el visitante: queda CONFIRMADO y avanza. */
    private void confirmar(Long partido, ResultadoRequest resultado) {
        resultadoService.cargar(escenario.jugador(partido, "LOCAL"), partido, resultado);
        resultadoService.aceptar(escenario.jugador(partido, "VISITANTE"), partido);
        refrescar();
    }

    private void avisarResultados() {
        eventos.stream(ResultadoConfirmado.class).skip(eventosDeAntes).forEach(avisos::avisar);
    }

    private Long llegarALaFinal() {
        confirmar(escenario.partido(torneo, 1, 1), new ResultadoRequest(2, 0, null, null));
        confirmar(escenario.partido(torneo, 1, 2), new ResultadoRequest(2, 0, null, null));
        return escenario.partido(torneo, 2, 1);
    }

    private String nombreDe(Long partido, String lado) {
        return nombre(escenario.jugador(partido, lado));
    }

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
