package com.hydra.pica.plataforma_pica.realtime.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

import com.hydra.pica.plataforma_pica.EscenarioTorneo;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.dto.BracketTorneo;
import com.hydra.pica.plataforma_pica.partido.dto.LadoBracket;
import com.hydra.pica.plataforma_pica.partido.dto.LaudoRequest;
import com.hydra.pica.plataforma_pica.partido.dto.ObjecionRequest;
import com.hydra.pica.plataforma_pica.partido.dto.PartidoBracket;
import com.hydra.pica.plataforma_pica.partido.dto.ResultadoRequest;
import com.hydra.pica.plataforma_pica.partido.event.PartidoSinPresentes;
import com.hydra.pica.plataforma_pica.partido.event.ResultadoConfirmado;
import com.hydra.pica.plataforma_pica.partido.event.ResultadoObjetado;
import com.hydra.pica.plataforma_pica.partido.service.AusenciaAutomatica;
import com.hydra.pica.plataforma_pica.partido.service.BracketService;
import com.hydra.pica.plataforma_pica.partido.service.CheckinService;
import com.hydra.pica.plataforma_pica.partido.service.EvidenciaService;
import com.hydra.pica.plataforma_pica.partido.service.LaudoService;
import com.hydra.pica.plataforma_pica.partido.service.ProgramacionService;
import com.hydra.pica.plataforma_pica.partido.service.ResultadoService;
import com.hydra.pica.plataforma_pica.realtime.dto.EventoTorneoMessage;
import com.hydra.pica.plataforma_pica.realtime.service.RealtimeNotifier;
import com.hydra.pica.plataforma_pica.torneo.dto.CheckinTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.service.TorneoService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

/**
 * El bracket en vivo (HU-809, PICA-434) contra Postgres: confirmar un resultado, ganar por ausencia o por pase directo
 * y corregir un resultado por laudo publican en el tópico del torneo el bracket actualizado con una versión del bracket
 * mayor, y el bracket que devuelve el GET trae esa misma versión. Como el test no confirma su transacción, los eventos
 * que se publicaron se le pasan a mano al listener del tiempo real, en orden, como haría el commit. Un torneo
 * individual de 4 en curso; cada test hace rollback al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, EscenarioTorneo.class})
@Transactional
@RecordApplicationEvents
class BracketEnVivoIntegracionTest {

    private static final byte[] JPG = Arrays.copyOf(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0},
            2048);
    private static final String URL = "http://localhost/api/v1/partidos/1/evidencias";

    @Autowired private RealtimeEventListener tiempoReal;
    @MockitoSpyBean private RealtimeNotifier notifier;
    @Autowired private BracketService bracketService;
    @Autowired private ResultadoService resultadoService;
    @Autowired private EvidenciaService evidenciaService;
    @Autowired private LaudoService laudoService;
    @Autowired private AusenciaAutomatica ausenciaAutomatica;
    @Autowired private CheckinService checkinService;
    @Autowired private ProgramacionService programacionService;
    @Autowired private TorneoService torneoService;
    @Autowired private ApplicationEvents eventos;
    @MockitoBean private EmailService emailService;
    @Autowired private EscenarioTorneo escenario;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private Long organizador;
    private Long arbitro;
    private Long torneo;
    /** El primer partido de la primera ronda. */
    private Long partido;
    /** Cuántos de los eventos registrados ya le llegaron al listener. */
    private long entregados;

    @BeforeEach
    void enCurso() {
        organizador = escenario.usuario("org_en_vivo", "ORGANIZADOR").getId();
        arbitro = escenario.usuario("juez_en_vivo", "ARBITRO").getId();
        torneo = escenario.individualEnCurso(organizador, arbitro, "Copa En Vivo", "jugador_en_vivo_", 4);
        escenario.empezadoAyer(torneo);
        partido = escenario.partido(torneo, 1, 1);
    }

    @Test
    void confirmarUnResultadoPublicaElAvanceConUnaVersionMayor() {
        long antes = versionActual();
        Long ganador = inscripcion(partido, "LOCAL");

        resultadoService.cargar(escenario.jugador(partido, "LOCAL"), partido, new ResultadoRequest(2, 0, null, null));
        resultadoService.aceptar(escenario.jugador(partido, "VISITANTE"), partido);

        EventoTorneoMessage mensaje = ultimoPublicado();
        assertThat(mensaje.tipo()).isEqualTo("RESULTADO_CONFIRMADO");
        assertThat(mensaje.partidoId()).isEqualTo(partido);
        assertThat(cruce(mensaje.bracket(), 1, 1).estado()).isEqualTo(EstadoPartido.CONFIRMADO);
        assertThat(cruce(mensaje.bracket(), 2, 1).local().inscripcionId()).isEqualTo(ganador);
        assertThat(mensaje.bracket().version()).isGreaterThan(antes);
        assertThat(versionActual()).isEqualTo(mensaje.bracket().version());
    }

    @Test
    void ganarPorAusenciaPublicaElWalkoverConUnaVersionMayor() {
        Long presente = inscripcion(partido, "VISITANTE");
        vencerLaVentana(partido, "VISITANTE");
        long antes = versionActual();

        ausenciaAutomatica.declararVencidas();

        EventoTorneoMessage mensaje = ultimoPublicado();
        assertThat(mensaje.partidoId()).isEqualTo(partido);
        assertThat(cruce(mensaje.bracket(), 1, 1).estado()).isEqualTo(EstadoPartido.WALKOVER);
        assertThat(cruce(mensaje.bracket(), 2, 1).local().inscripcionId()).isEqualTo(presente);
        assertThat(mensaje.bracket().version()).isGreaterThan(antes);
        assertThat(versionActual()).isEqualTo(mensaje.bracket().version());
    }

    @Test
    void elPaseDirectoPublicaAlCampeonConUnaVersionMayor() {
        Long hermano = escenario.partido(torneo, 1, 2);
        Long campeon = inscripcion(hermano, "LOCAL");
        resultadoService.cargar(escenario.jugador(hermano, "LOCAL"), hermano, new ResultadoRequest(1, 0, null, null));
        resultadoService.aceptar(escenario.jugador(hermano, "VISITANTE"), hermano);
        torneoService.configurarCheckin(organizador, torneo, new CheckinTorneoRequest(30, 20, "DESCALIFICAR_AMBOS"));
        vencerLaVentana(partido);
        entregarAlTiempoReal();
        long antes = versionActual();

        ausenciaAutomatica.declararVencidas();

        Long laFinal = escenario.partido(torneo, 2, 1);
        EventoTorneoMessage mensaje = ultimoPublicado();
        assertThat(mensaje.partidoId()).isEqualTo(laFinal);
        assertThat(mensaje.torneoFinalizado()).isTrue();
        assertThat(cruce(mensaje.bracket(), 2, 1).visitante())
                .extracting(LadoBracket::inscripcionId, LadoBracket::ganador).containsExactly(campeon, true);
        assertThat(mensaje.bracket().version()).isGreaterThan(antes);
        assertThat(versionActual()).isEqualTo(mensaje.bracket().version());
    }

    @Test
    void unLaudoQueCorrigePublicaAlGanadorNuevoConUnaVersionMayor() {
        Long local = escenario.jugador(partido, "LOCAL");
        Long visitante = escenario.jugador(partido, "VISITANTE");
        resultadoService.cargar(local, partido, new ResultadoRequest(2, 0, null, null));
        Long evidencia = evidenciaService.subir(visitante, partido, JPG, URL).id();
        resultadoService.objetar(visitante, partido, new ObjecionRequest("No fue así", 0, 1, null, null));
        entregarAlTiempoReal();
        long antes = versionActual();
        Long ganador = inscripcion(partido, "VISITANTE");

        laudoService.laudar(organizador, partido,
                new LaudoRequest(0, 1, null, null, "La planilla dice 0-1", evidencia));

        EventoTorneoMessage mensaje = ultimoPublicado();
        assertThat(mensaje.partidoId()).isEqualTo(partido);
        assertThat(cruce(mensaje.bracket(), 1, 1).estado()).isEqualTo(EstadoPartido.CORREGIDO);
        assertThat(cruce(mensaje.bracket(), 2, 1).local().inscripcionId()).isEqualTo(ganador);
        assertThat(mensaje.bracket().version()).isGreaterThan(antes);
        assertThat(versionActual()).isEqualTo(mensaje.bracket().version());
    }

    // --- el resto de los cambios de la llave -----------------------------------------------

    @Test
    void cargarUnResultadoYObjetarloSubenLaVersion() {
        Long local = escenario.jugador(partido, "LOCAL");
        Long visitante = escenario.jugador(partido, "VISITANTE");
        long antes = versionActual();

        resultadoService.cargar(local, partido, new ResultadoRequest(2, 0, null, null));
        long cargado = versionActual();
        evidenciaService.subir(visitante, partido, JPG, URL);
        resultadoService.objetar(visitante, partido, new ObjecionRequest("No fue así", 0, 1, null, null));

        assertThat(cargado).isGreaterThan(antes);
        assertThat(versionActual()).isGreaterThan(cargado);
    }

    @Test
    void programarElPartidoYHacerCheckinSubenLaVersion() {
        torneoService.configurarCheckin(organizador, torneo, new CheckinTorneoRequest(30, 30));
        long antes = versionActual();

        programacionService.programar(organizador, partido, Instant.now().plus(5, ChronoUnit.MINUTES));
        long programado = versionActual();
        checkinService.hacer(escenario.jugador(partido, "LOCAL"), partido);

        assertThat(programado).isGreaterThan(antes);
        assertThat(versionActual()).isGreaterThan(programado);
    }

    @Test
    void unPartidoSinPresentesQueSeReprogramaSubeLaVersion() {
        vencerLaVentana(partido);
        long antes = versionActual();

        ausenciaAutomatica.declararVencidas();

        EventoTorneoMessage mensaje = ultimoPublicado();
        assertThat(mensaje.tipo()).isEqualTo("PARTIDO_SIN_PRESENTES");
        assertThat(cruce(mensaje.bracket(), 1, 1).fechaProgramada()).isNull();
        assertThat(mensaje.bracket().version()).isGreaterThan(antes);
    }

    @Test
    void volverASortearSubeLaVersion() {
        long antes = versionActual();

        BracketTorneo sorteado = bracketService.regenerar(organizador, torneo);

        assertThat(sorteado.version()).isGreaterThan(antes);
        assertThat(versionActual()).isEqualTo(sorteado.version());
    }

    @Test
    void configurarElTorneoNoTocaLaVersion() {
        long antes = versionActual();

        torneoService.configurarCheckin(organizador, torneo, new CheckinTorneoRequest(30, 20, "DESCALIFICAR_AMBOS"));

        assertThat(versionActual()).isEqualTo(antes);
    }

    // --- ayudas -------------------------------------------------------------------------

    /** Lo que el commit le haría llegar al listener: los eventos del bracket que todavía no le llegaron, en orden. */
    private void entregarAlTiempoReal() {
        entityManager.flush();
        entityManager.clear();
        List<Object> pendientes = eventos.stream().skip(entregados)
                .map(e -> e instanceof PayloadApplicationEvent<?> p ? p.getPayload() : e)
                .toList();
        entregados += pendientes.size();
        for (Object evento : pendientes) {
            switch (evento) {
                case ResultadoConfirmado e -> tiempoReal.onResultadoConfirmado(e);
                case ResultadoObjetado e -> tiempoReal.onResultadoObjetado(e);
                case PartidoSinPresentes e -> tiempoReal.onPartidoSinPresentes(e);
                default -> { }
            }
        }
    }

    /** El último mensaje que llegó al tópico del torneo después de entregarle al listener lo pendiente. */
    private EventoTorneoMessage ultimoPublicado() {
        clearInvocations(notifier);
        entregarAlTiempoReal();
        ArgumentCaptor<EventoTorneoMessage> mensajes = ArgumentCaptor.forClass(EventoTorneoMessage.class);
        verify(notifier, atLeastOnce()).publicarTorneo(eq(torneo), mensajes.capture());
        return mensajes.getValue();
    }

    /** La versión del bracket que devuelve el GET. */
    private long versionActual() {
        entityManager.flush();
        entityManager.clear();
        return bracketService.ver(torneo).version();
    }

    private static PartidoBracket cruce(BracketTorneo bracket, int ronda, int posicion) {
        return bracket.rondas().get(ronda - 1).partidos().stream()
                .filter(p -> p.posicion() == posicion)
                .findFirst()
                .orElseThrow();
    }

    /** Programa el partido dentro de 5 minutos, hacen check-in los presentes y la ventana cierra hace 5. */
    private void vencerLaVentana(Long partidoId, String... presentes) {
        torneoService.configurarCheckin(organizador, torneo, new CheckinTorneoRequest(30, 30));
        programacionService.programar(organizador, partidoId, Instant.now().plus(5, ChronoUnit.MINUTES));
        for (String lado : presentes) {
            checkinService.hacer(escenario.jugador(partidoId, lado), partidoId);
        }
        torneoService.configurarCheckin(organizador, torneo, new CheckinTorneoRequest(30, 20));
        entityManager.flush();
        entityManager.clear();
    }

    private Long inscripcion(Long partidoId, String lado) {
        return jdbc.queryForObject("SELECT inscripcion_id FROM juega WHERE partido_id = ? AND lado = ?", Long.class,
                partidoId, lado);
    }
}
