package com.hydra.pica.plataforma_pica.reputacion.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.hydra.pica.plataforma_pica.EscenarioTorneo;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionEquipoRequest;
import com.hydra.pica.plataforma_pica.inscripcion.dto.ReemplazoRequest;
import com.hydra.pica.plataforma_pica.inscripcion.service.InscripcionService;
import com.hydra.pica.plataforma_pica.partido.dto.LaudoRequest;
import com.hydra.pica.plataforma_pica.partido.dto.ObjecionRequest;
import com.hydra.pica.plataforma_pica.partido.dto.PartidoBracket;
import com.hydra.pica.plataforma_pica.partido.dto.ResultadoRequest;
import com.hydra.pica.plataforma_pica.partido.service.AusenciaAutomatica;
import com.hydra.pica.plataforma_pica.partido.service.BracketService;
import com.hydra.pica.plataforma_pica.partido.service.CheckinService;
import com.hydra.pica.plataforma_pica.partido.service.EvidenciaService;
import com.hydra.pica.plataforma_pica.partido.service.LaudoService;
import com.hydra.pica.plataforma_pica.partido.service.ProgramacionService;
import com.hydra.pica.plataforma_pica.partido.service.ResultadoService;
import com.hydra.pica.plataforma_pica.reputacion.dto.HistorialCompetitivo;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.PermisoArbitro;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.dto.CheckinTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.DesignarArbitroRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.InformacionTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.SolicitudCancelacion;
import com.hydra.pica.plataforma_pica.torneo.service.ArbitrosTorneoService;
import com.hydra.pica.plataforma_pica.torneo.service.CancelacionTorneoService;
import com.hydra.pica.plataforma_pica.torneo.service.TorneoService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Las pruebas de aceptación del historial competitivo (HU-801, T801-1, PICA-418): el escenario se arma con los
 * servicios de la aplicación y el historial solo se pregunta al servicio.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, EscenarioTorneo.class})
@Transactional
class HistorialCompetitivoIntegracionTest {

    private static final byte[] JPG = Arrays.copyOf(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0},
            2048);
    private static final String URL = "http://localhost/api/v1/partidos/1/evidencias";

    @Autowired private HistorialCompetitivoService historialService;
    @Autowired private ResultadoService resultadoService;
    @Autowired private EvidenciaService evidenciaService;
    @Autowired private LaudoService laudoService;
    @Autowired private BracketService bracketService;
    @Autowired private TorneoService torneoService;
    @Autowired private CancelacionTorneoService cancelacionService;
    @Autowired private ArbitrosTorneoService arbitrosService;
    @Autowired private InscripcionService inscripcionService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProgramacionService programacionService;
    @Autowired private CheckinService checkinService;
    @Autowired private AusenciaAutomatica ausenciaAutomatica;
    @Autowired private EscenarioTorneo escenario;
    @Autowired private EntityManager entityManager;

    private Long organizador;
    private Long arbitro;
    private Long torneo;
    /** El primer partido de la primera ronda y los usuarios de cada lado. */
    private Long partido;
    private Long local;
    private Long visitante;

    /** Un torneo individual de 4 en curso, con el bracket generado. */
    @BeforeEach
    void enCurso() {
        organizador = escenario.usuario("org_historial", "ORGANIZADOR").getId();
        arbitro = escenario.usuario("juez_historial", "ARBITRO").getId();
        torneo = escenario.individualEnCurso(organizador, arbitro, "Copa Historial", "jugador_historial_", 4);
        partido = escenario.partido(torneo, 1, 1);
        local = escenario.jugador(partido, "LOCAL");
        visitante = escenario.jugador(partido, "VISITANTE");
    }

    @Test
    void unJugadorQueNuncaJugoTieneTodoEnCero() {
        Long nuevo = escenario.usuario("jugador_nuevo", "PARTICIPANTE").getId();

        assertThat(historialService.de(nuevo)).isEqualTo(new HistorialCompetitivo(0, 0, 0, 0));
    }

    @Test
    void unResultadoConfirmadoSumaUnaVictoriaAlGanadorYUnaDerrotaAlPerdedor() {
        jugar(partido, 2, 0);

        assertThat(historialService.de(local)).isEqualTo(new HistorialCompetitivo(0, 1, 0, 0));
        assertThat(historialService.de(visitante)).isEqualTo(new HistorialCompetitivo(0, 0, 1, 0));
    }

    @Test
    void unResultadoPendienteUObjetadoNoSuma() {
        resultadoService.cargar(local, partido, resultado(2, 0));
        entityManager.flush();

        assertThat(historialService.de(local)).isEqualTo(new HistorialCompetitivo(0, 0, 0, 0));
        assertThat(historialService.de(visitante)).isEqualTo(new HistorialCompetitivo(0, 0, 0, 0));

        objetar();

        assertThat(historialService.de(local)).isEqualTo(new HistorialCompetitivo(0, 0, 0, 0));
        assertThat(historialService.de(visitante)).isEqualTo(new HistorialCompetitivo(0, 0, 0, 0));
    }

    @Test
    void elByeNoSuma() {
        Long deTres = escenario.individualEnCurso(organizador, arbitro, "Copa de tres", "jugador_bye_", 3);
        Long bye = bracketService.ver(deTres).rondas().getFirst().partidos().stream()
                .filter(PartidoBracket::bye).findFirst().orElseThrow().id();

        assertThat(historialService.de(escenario.jugador(bye, "LOCAL")))
                .isEqualTo(new HistorialCompetitivo(0, 0, 0, 0));
    }

    @Test
    void unWalkoverSumaUnaVictoriaAlQueHizoCheckinYUnaDerrotaAlAusente() {
        abrirLaVentana("LOCAL");

        cerrarLaVentana();

        assertThat(historialService.de(local)).isEqualTo(new HistorialCompetitivo(0, 1, 0, 0));
        assertThat(historialService.de(visitante)).isEqualTo(new HistorialCompetitivo(0, 0, 1, 0));
    }

    @Test
    void siSeAusentanLosDosConDescalificarAmbosCadaUnoSumaUnaDerrota() {
        torneoService.configurarCheckin(organizador, torneo, new CheckinTorneoRequest(30, 20, "DESCALIFICAR_AMBOS"));
        abrirLaVentana();

        cerrarLaVentana();

        assertThat(historialService.de(local)).isEqualTo(new HistorialCompetitivo(0, 0, 1, 0));
        assertThat(historialService.de(visitante)).isEqualTo(new HistorialCompetitivo(0, 0, 1, 0));
    }

    @Test
    void unLaudoQueCorrigeElGanadorLePasaLaVictoriaAlOtroLado() {
        resultadoService.cargar(local, partido, resultado(2, 0));
        Long evidencia = objetar();
        escenario.habilitarArbitro(torneo);

        laudoService.laudar(arbitro, partido, new LaudoRequest(0, 1, null, null, "La planilla dice 0-1", evidencia));
        entityManager.flush();

        assertThat(historialService.de(local)).isEqualTo(new HistorialCompetitivo(0, 0, 1, 0));
        assertThat(historialService.de(visitante)).isEqualTo(new HistorialCompetitivo(0, 1, 0, 0));
    }

    @Test
    void alFinalizarTodosSumanUnTorneoJugadoYSubenAlPodioLosTresPrimeros() {
        Long deOcho = escenario.individualEnCurso(organizador, arbitro, "Copa de ocho", "jugador_podio_", 8);
        for (int ronda = 1; ronda <= 3; ronda++) {
            for (int posicion = 1; posicion <= 4 >> (ronda - 1); posicion++) {
                jugar(escenario.partido(deOcho, ronda, posicion), 1, 0);
            }
        }
        Long laFinal = escenario.partido(deOcho, 3, 1);

        assertThat(historialService.de(escenario.jugador(laFinal, "LOCAL")))
                .isEqualTo(new HistorialCompetitivo(1, 3, 0, 1));
        assertThat(historialService.de(escenario.jugador(laFinal, "VISITANTE")))
                .isEqualTo(new HistorialCompetitivo(1, 2, 1, 1));
        for (int posicion = 1; posicion <= 2; posicion++) {
            assertThat(historialService.de(escenario.jugador(escenario.partido(deOcho, 2, posicion), "VISITANTE")))
                    .isEqualTo(new HistorialCompetitivo(1, 1, 1, 1));
        }
        for (int posicion = 1; posicion <= 4; posicion++) {
            assertThat(historialService.de(escenario.jugador(escenario.partido(deOcho, 1, posicion), "VISITANTE")))
                    .isEqualTo(new HistorialCompetitivo(1, 0, 1, 0));
        }
    }

    @Test
    void unTorneoCanceladoNoCuentaComoJugadoPeroLosPartidosQueSeCerraronSi() {
        jugar(partido, 2, 0);

        cancelacionService.cancelar(organizador, torneo, new SolicitudCancelacion("Se suspendió el evento", null));
        entityManager.flush();

        assertThat(historialService.de(local)).isEqualTo(new HistorialCompetitivo(0, 1, 0, 0));
        assertThat(historialService.de(visitante)).isEqualTo(new HistorialCompetitivo(0, 0, 1, 0));
    }

    @Test
    void enUnTorneoPorEquiposCadaUnoDeLaNominaSumaLoDelEquipoYElQueSalioPorUnReemplazoNo() {
        Long porEquipos = torneoPorEquipos();
        Long capitanA = escenario.usuario("capitan_a", "PARTICIPANTE").getId();
        Long sale = escenario.usuario("sale_a", "PARTICIPANTE").getId();
        Long entra = escenario.usuario("entra_a", "PARTICIPANTE").getId();
        Long capitanB = escenario.usuario("capitan_b", "PARTICIPANTE").getId();
        Long companieroB = escenario.usuario("companiero_b", "PARTICIPANTE").getId();
        Long equipoA = inscribirEquipo(porEquipos, capitanA, "Los A", sale);
        inscribirEquipo(porEquipos, capitanB, "Los B", companieroB);
        inscripcionService.reemplazar(capitanA, equipoA, new ReemplazoRequest(sale, entra));
        cerrarYSortear(porEquipos);
        PartidoBracket laFinal = bracketService.ver(porEquipos).rondas().getFirst().partidos().getFirst();
        Map<String, List<Long>> nominas = Map.of(
                "Los A", List.of(capitanA, entra),
                "Los B", List.of(capitanB, companieroB));
        List<Long> ganadores = nominas.get(laFinal.local().nombre());
        List<Long> perdedores = nominas.get(laFinal.visitante().nombre());

        resultadoService.cargar(ganadores.getFirst(), laFinal.id(), resultado(1, 0));
        resultadoService.aceptar(perdedores.getFirst(), laFinal.id());
        entityManager.flush();

        for (Long ganador : ganadores) {
            assertThat(historialService.de(ganador)).isEqualTo(new HistorialCompetitivo(1, 1, 0, 1));
        }
        for (Long perdedor : perdedores) {
            assertThat(historialService.de(perdedor)).isEqualTo(new HistorialCompetitivo(1, 0, 1, 1));
        }
        assertThat(historialService.de(sale)).isEqualTo(new HistorialCompetitivo(0, 0, 0, 0));
    }

    // --- helpers --------------------------------------------------------------

    /** Un torneo gratis por equipos de 2, con la inscripción abierta y el árbitro designado (aceptó). */
    private Long torneoPorEquipos() {
        Long id = torneoService.crear(organizador, new InformacionTorneoRequest("Copa por equipos", "Futbol", null,
                null, Modalidad.ONLINE, TipoParticipante.EQUIPO, null, null, Instant.now().plus(14, ChronoUnit.DAYS),
                null, Instant.now().plus(7, ChronoUnit.DAYS), "Partidos de 20 minutos", 8, 2, 2, 2)).id();
        arbitrosService.designar(organizador, id,
                new DesignarArbitroRequest(arbitro, Set.of(PermisoArbitro.CARGAR_RESULTADOS)));
        jdbc.update("UPDATE torneo_arbitro SET estado = 'ACEPTADO' WHERE torneo_id = ?", id);
        jdbc.update("""
                UPDATE torneo SET valor_inscripcion = 0, pct_pozo_competidores = 90, pct_premio_rifa = 0,
                                  estado = 'INSCRIPCION_ABIERTA', publicado_en = now()
                WHERE id = ?""", id);
        entityManager.clear();
        return id;
    }

    /** El capitán inscribe un equipo nuevo con él y el compañero, y confirma: el torneo es gratis. */
    private Long inscribirEquipo(Long torneoId, Long capitan, String nombre, Long companiero) {
        Long id = inscripcionService.crear(capitan, torneoId,
                new InscripcionEquipoRequest(null, nombre, List.of(capitan, companiero))).id();
        inscripcionService.confirmar(capitan, id, true);
        return id;
    }

    /** Cierra la inscripción y genera el bracket. */
    private void cerrarYSortear(Long torneoId) {
        jdbc.update("UPDATE torneo SET estado = 'INSCRIPCION_CERRADA' WHERE id = ?", torneoId);
        entityManager.flush();
        entityManager.clear();
        bracketService.generar(organizador, torneoId);
        entityManager.flush();
        entityManager.clear();
    }

    /** Carga el resultado desde el local y lo acepta el visitante. */
    private void jugar(Long partidoId, int golesLocal, int golesVisitante) {
        resultadoService.cargar(escenario.jugador(partidoId, "LOCAL"), partidoId,
                resultado(golesLocal, golesVisitante));
        resultadoService.aceptar(escenario.jugador(partidoId, "VISITANTE"), partidoId);
        entityManager.flush();
    }

    /** Con el 2-0 del local cargado, el visitante lo objeta con una foto: devuelve la foto. */
    private Long objetar() {
        Long evidencia = evidenciaService.subir(visitante, partido, JPG, URL).id();
        resultadoService.objetar(visitante, partido,
                new ObjecionRequest("Terminó 0-1", 0, 1, null, null));
        entityManager.flush();
        return evidencia;
    }

    /**
     * Programa el primer partido dentro de 5 minutos con una ventana de check-in de 30 y 30, abierta, y hacen check-in
     * los {@code presentes}.
     */
    private void abrirLaVentana(String... presentes) {
        escenario.empezadoAyer(torneo);
        torneoService.configurarCheckin(organizador, torneo, new CheckinTorneoRequest(30, 30));
        programacionService.programar(organizador, partido, Instant.now().plus(5, ChronoUnit.MINUTES));
        for (String lado : presentes) {
            checkinService.hacer(escenario.jugador(partido, lado), partido);
        }
    }

    /** Achica la ventana a 30 y 20 (cerró hace 5 minutos) y declara las ausencias vencidas. */
    private void cerrarLaVentana() {
        torneoService.configurarCheckin(organizador, torneo, new CheckinTorneoRequest(30, 20));
        entityManager.flush();
        entityManager.clear();
        ausenciaAutomatica.declararVencidas();
        entityManager.flush();
    }

    private static ResultadoRequest resultado(int local, int visitante) {
        return new ResultadoRequest(local, visitante, null, null);
    }
}
