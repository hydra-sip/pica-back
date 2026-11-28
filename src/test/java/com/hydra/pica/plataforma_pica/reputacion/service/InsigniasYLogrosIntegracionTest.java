package com.hydra.pica.plataforma_pica.reputacion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.hydra.pica.plataforma_pica.EscenarioTorneo;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.billetera.event.TorneoLiquidado;
import com.hydra.pica.plataforma_pica.billetera.service.LiquidacionService;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionEquipoRequest;
import com.hydra.pica.plataforma_pica.inscripcion.dto.ReemplazoRequest;
import com.hydra.pica.plataforma_pica.inscripcion.service.InscripcionService;
import com.hydra.pica.plataforma_pica.inscripcion.service.ReemplazoInscripcionService;
import com.hydra.pica.plataforma_pica.partido.dto.PartidoBracket;
import com.hydra.pica.plataforma_pica.partido.dto.ResultadoRequest;
import com.hydra.pica.plataforma_pica.partido.event.CheckinHecho;
import com.hydra.pica.plataforma_pica.partido.event.ResultadoConfirmado;
import com.hydra.pica.plataforma_pica.partido.service.BracketService;
import com.hydra.pica.plataforma_pica.partido.service.CheckinService;
import com.hydra.pica.plataforma_pica.partido.service.ProgramacionService;
import com.hydra.pica.plataforma_pica.partido.service.ResultadoService;
import com.hydra.pica.plataforma_pica.reputacion.domain.CodigoInsignia;
import com.hydra.pica.plataforma_pica.reputacion.domain.Insignia;
import com.hydra.pica.plataforma_pica.reputacion.dto.InsigniaObtenida;
import com.hydra.pica.plataforma_pica.reputacion.dto.LogrosJugador;
import com.hydra.pica.plataforma_pica.reputacion.dto.RifaGanada;
import com.hydra.pica.plataforma_pica.rifa.dto.PremioRifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaRequest;
import com.hydra.pica.plataforma_pica.rifa.event.RifaSorteada;
import com.hydra.pica.plataforma_pica.rifa.service.CierreVentaService;
import com.hydra.pica.plataforma_pica.rifa.service.Drand;
import com.hydra.pica.plataforma_pica.rifa.service.RifaService;
import com.hydra.pica.plataforma_pica.rifa.service.SorteoRifaService;
import com.hydra.pica.plataforma_pica.rifa.service.VentaRifaService;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.PermisoArbitro;
import com.hydra.pica.plataforma_pica.torneo.domain.SelloTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.dto.CheckinTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.DesignarArbitroRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.InformacionTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.service.ArbitrosTorneoService;
import com.hydra.pica.plataforma_pica.torneo.service.TorneoService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rifas ganadas e insignias por eventos (HU-804, T804-1, PICA-425) contra Postgres: catálogo inicial de V64,
 * consulta de premios de rifas sorteadas y reglas automáticas e idempotentes sobre resultados, liquidación, sorteos y
 * rachas de check-in.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, EscenarioTorneo.class})
@Transactional
@RecordApplicationEvents
class InsigniasYLogrosIntegracionTest {

    private static final Instant MANIANA = Instant.now().plus(1, ChronoUnit.DAYS);
    private static final Instant EN_CINCO_DIAS = Instant.now().plus(5, ChronoUnit.DAYS);
    private static final Instant EN_SEIS_DIAS = Instant.now().plus(6, ChronoUnit.DAYS);
    private static final Instant EN_UNA_SEMANA = Instant.now().plus(7, ChronoUnit.DAYS);
    private static final Instant EN_DOS_SEMANAS = Instant.now().plus(14, ChronoUnit.DAYS);

    @Autowired private InsigniaService insigniaService;
    @Autowired private TorneoService torneoService;
    @Autowired private ArbitrosTorneoService arbitrosService;
    @Autowired private InscripcionService inscripcionService;
    @Autowired private ReemplazoInscripcionService reemplazoInscripcionService;
    @Autowired private BracketService bracketService;
    @Autowired private ResultadoService resultadoService;
    @Autowired private LiquidacionService liquidacionService;
    @Autowired private ProgramacionService programacionService;
    @Autowired private CheckinService checkinService;
    @Autowired private RifaService rifaService;
    @Autowired private VentaRifaService ventaRifaService;
    @Autowired private CierreVentaService cierreVentaService;
    @Autowired private SorteoRifaService sorteoRifaService;
    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private TransactionTemplate transaccion;
    @Autowired private EscenarioTorneo escenario;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private ApplicationEvents eventos;

    @MockitoSpyBean private Drand drand;

    private Long organizador;
    private Long arbitro;

    @BeforeEach
    void preparar() {
        doAnswer(i -> {
            long ronda = i.getArgument(0);
            return new Drand.Pulso(ronda, SelloTorneo.sha256("ronda:" + ronda), "firma");
        }).when(drand).pulso(anyLong());

        organizador = escenario.usuario("org_insignia_" + sufijo(), "ORGANIZADOR").getId();
        arbitro = escenario.usuario("juez_insignia_" + sufijo(), "ARBITRO").getId();
    }

    @Test
    void elCatalogoTieneLasCincoInsigniasInicialesYUnUsuarioNuevoNoTieneLogros() {
        Long nuevo = escenario.usuario("nuevo_insignia_" + sufijo(), "PARTICIPANTE").getId();

        assertThat(insigniaService.catalogo())
                .extracting(Insignia::getCodigo)
                .containsExactlyInAnyOrder(
                        CodigoInsignia.DIEZ_CHECKINS_SEGUIDOS.name(),
                        CodigoInsignia.PRIMERA_RIFA_GANADA.name(),
                        CodigoInsignia.PRIMER_PODIO.name(),
                        CodigoInsignia.PRIMER_TORNEO_GANADO.name(),
                        CodigoInsignia.PRIMER_TORNEO_JUGADO.name());
        assertThat(insigniaService.insigniasDe(nuevo)).isEmpty();
        assertThat(insigniaService.rifasGanadas(nuevo)).isEmpty();
        assertThat(insigniaService.ver(nuevo)).isEqualTo(new LogrosJugador(nuevo, List.of(), List.of()));
        assertThatThrownBy(() -> insigniaService.ver(999_999_999L))
                .isInstanceOf(NoEncontradoException.class)
                .extracting("codigo")
                .isEqualTo(CodigoError.USUARIO_NO_ENCONTRADO);
    }

    @Test
    void alSortearRifaAsociadaYSueltaSeListanLasRifasGanadasYSeOtorgaLaInsigniaUnaSolaVez() {
        Long comprador = escenario.usuario("comprador_rifa_" + sufijo(), "PARTICIPANTE").getId();
        Long torneoAsociado = torneoService.crear(organizador, new InformacionTorneoRequest("Copa Rifa Premio",
                "Ajedrez", null, null, Modalidad.ONLINE, TipoParticipante.INDIVIDUAL, null, null, EN_DOS_SEMANAS,
                null, EN_UNA_SEMANA, "Reglas", 8, 2, null, null)).id();
        jdbc.update("""
                UPDATE torneo SET valor_inscripcion = 0,
                                  estado = 'INSCRIPCION_ABIERTA',
                                  publicado_en = now(),
                                  slug = 'copa-rifa-premio-' || id
                WHERE id = ?""", torneoAsociado);
        entityManager.clear();

        RifaDetalle rifaTorneo = crearYPublicarRifa("Rifa de la Copa", torneoAsociado,
                List.of(new PremioRifaRequest(new BigDecimal("100"), "Premio único")));
        comprarYSortear(rifaTorneo.id(), comprador, 7);

        RifaDetalle rifaSuelta = crearYPublicarRifa("Rifa Abierta", null,
                List.of(new PremioRifaRequest(new BigDecimal("100"), "Voucher")));
        comprarYSortear(rifaSuelta.id(), comprador, 14);

        // Una rifa cancelada aunque tenga ganador residual no debe figurar entre las rifas ganadas
        RifaDetalle rifaCancelada = crearYPublicarRifa("Rifa Cancelada", null,
                List.of(new PremioRifaRequest(new BigDecimal("100"), "No vale")));
        comprar(rifaCancelada.id(), comprador, 21);
        jdbc.update("UPDATE premio_rifa SET numero_ganador = 21 WHERE rifa_id = ?", rifaCancelada.id());
        jdbc.update("UPDATE rifa SET estado = 'CANCELADA' WHERE id = ?", rifaCancelada.id());
        entityManager.flush();
        entityManager.clear();

        assertThat(eventos.stream(RifaSorteada.class))
                .extracting(RifaSorteada::rifaId)
                .contains(rifaTorneo.id(), rifaSuelta.id());

        insigniaService.evaluarRifaSorteada(rifaTorneo.id());
        insigniaService.evaluarRifaSorteada(rifaSuelta.id());
        entityManager.flush();
        entityManager.clear();

        List<RifaGanada> ganadas = insigniaService.rifasGanadas(comprador);
        assertThat(ganadas)
                .extracting(RifaGanada::rifaId, RifaGanada::rifaTitulo, RifaGanada::torneoId,
                        RifaGanada::torneoNombre, RifaGanada::puesto, RifaGanada::numeroGanador)
                .containsExactlyInAnyOrder(
                        tuple(rifaTorneo.id(), "Rifa de la Copa", torneoAsociado, "Copa Rifa Premio", 1, 7),
                        tuple(rifaSuelta.id(), "Rifa Abierta", null, null, 1, 14));
        assertThat(ganadas).allSatisfy(g -> assertThat(g.fecha()).isNotNull());
        assertThat(ganadas.stream().filter(g -> g.rifaId().equals(rifaTorneo.id())).findFirst().orElseThrow()
                .torneoSlug()).isNotBlank();

        assertThat(insigniaService.insigniasDe(comprador))
                .extracting(i -> CodigoInsignia.valueOf(i.codigo()))
                .containsExactly(CodigoInsignia.PRIMERA_RIFA_GANADA);
    }

    @Test
    void alCerrarPartidosYFinalizarTorneoIndividualOtorgaParticipacionPodioYGanadorSinDuplicar() {
        Long torneo8 = escenario.individualEnCurso(organizador, arbitro, "Copa Insignia Ocho",
                "jug_ocho_" + sufijo() + "_", 8);

        // Cuartos de final: solo confirma un partido, sin finalizar el torneo
        Long primerCruce = escenario.partido(torneo8, 1, 1);
        Long ganadorCuarto = escenario.jugador(primerCruce, "LOCAL");
        Long perdedorCuarto = escenario.jugador(primerCruce, "VISITANTE");
        jugar(primerCruce, 2, 0);
        insigniaService.evaluarResultadoConfirmado(torneo8, primerCruce, false);

        assertThat(insigniaService.insigniasDe(ganadorCuarto))
                .extracting(i -> CodigoInsignia.valueOf(i.codigo()))
                .containsExactly(CodigoInsignia.PRIMER_TORNEO_JUGADO);
        assertThat(insigniaService.insigniasDe(perdedorCuarto))
                .extracting(i -> CodigoInsignia.valueOf(i.codigo()))
                .containsExactly(CodigoInsignia.PRIMER_TORNEO_JUGADO);

        // Completa el resto de la llave hasta la final
        for (int ronda = 1; ronda <= 3; ronda++) {
            for (int posicion = 1; posicion <= 4 >> (ronda - 1); posicion++) {
                if (ronda == 1 && posicion == 1) {
                    continue;
                }
                jugar(escenario.partido(torneo8, ronda, posicion), 1, 0);
            }
        }
        Long laFinal = escenario.partido(torneo8, 3, 1);
        assertThat(eventos.stream(ResultadoConfirmado.class))
                .anyMatch(e -> e.partidoId().equals(laFinal) && e.torneoFinalizado());

        // Simula evaluación tanto al confirmar la final como tras liquidar el torneo: no debe duplicar insignias
        insigniaService.evaluarResultadoConfirmado(torneo8, laFinal, true);
        liquidacionService.liquidarTorneo(torneo8);
        assertThat(eventos.stream(TorneoLiquidado.class))
                .anyMatch(e -> e.torneoId().equals(torneo8));
        insigniaService.evaluarTorneoFinalizado(torneo8);
        entityManager.flush();
        entityManager.clear();

        Long campeon = escenario.jugador(laFinal, "LOCAL");
        Long subcampeon = escenario.jugador(laFinal, "VISITANTE");
        assertThat(insigniaService.insigniasDe(campeon))
                .extracting(i -> CodigoInsignia.valueOf(i.codigo()))
                .containsExactlyInAnyOrder(
                        CodigoInsignia.PRIMER_TORNEO_JUGADO,
                        CodigoInsignia.PRIMER_PODIO,
                        CodigoInsignia.PRIMER_TORNEO_GANADO);
        assertThat(insigniaService.insigniasDe(subcampeon))
                .extracting(i -> CodigoInsignia.valueOf(i.codigo()))
                .containsExactlyInAnyOrder(
                        CodigoInsignia.PRIMER_TORNEO_JUGADO,
                        CodigoInsignia.PRIMER_PODIO);
        for (int pos = 1; pos <= 2; pos++) {
            Long tercero = escenario.jugador(escenario.partido(torneo8, 2, pos), "VISITANTE");
            assertThat(insigniaService.insigniasDe(tercero))
                    .extracting(i -> CodigoInsignia.valueOf(i.codigo()))
                    .containsExactlyInAnyOrder(
                            CodigoInsignia.PRIMER_TORNEO_JUGADO,
                            CodigoInsignia.PRIMER_PODIO);
        }
        for (int pos = 1; pos <= 4; pos++) {
            Long eliminadoCuartos = escenario.jugador(escenario.partido(torneo8, 1, pos), "VISITANTE");
            assertThat(insigniaService.insigniasDe(eliminadoCuartos))
                    .extracting(i -> CodigoInsignia.valueOf(i.codigo()))
                    .containsExactly(CodigoInsignia.PRIMER_TORNEO_JUGADO);
        }
    }

    @Test
    void enTorneoPorEquiposLasInsigniasLleganALaNominaVigenteYNoAlReemplazado() {
        Long torneoEq = torneoPorEquipos();
        Long capitanA = escenario.usuario("cap_eq_a_" + sufijo(), "PARTICIPANTE").getId();
        Long saleA = escenario.usuario("sale_eq_a_" + sufijo(), "PARTICIPANTE").getId();
        Long entraA = escenario.usuario("entra_eq_a_" + sufijo(), "PARTICIPANTE").getId();
        Long capitanB = escenario.usuario("cap_eq_b_" + sufijo(), "PARTICIPANTE").getId();
        Long compB = escenario.usuario("comp_eq_b_" + sufijo(), "PARTICIPANTE").getId();

        Long eqA = inscribirEquipo(torneoEq, capitanA, "Equipo Alfa", saleA);
        inscribirEquipo(torneoEq, capitanB, "Equipo Beta", compB);
        reemplazoInscripcionService.reemplazar(capitanA, eqA, new ReemplazoRequest(saleA, entraA));

        jdbc.update("UPDATE torneo SET estado = 'INSCRIPCION_CERRADA' WHERE id = ?", torneoEq);
        entityManager.flush();
        entityManager.clear();
        bracketService.generar(organizador, torneoEq);
        entityManager.flush();
        entityManager.clear();

        PartidoBracket laFinal = bracketService.ver(torneoEq).rondas().getFirst().partidos().getFirst();
        Map<String, List<Long>> nominas = Map.of(
                "Equipo Alfa", List.of(capitanA, entraA),
                "Equipo Beta", List.of(capitanB, compB));
        List<Long> ganadores = nominas.get(laFinal.local().nombre());
        List<Long> perdedores = nominas.get(laFinal.visitante().nombre());

        resultadoService.cargar(ganadores.getFirst(), laFinal.id(), new ResultadoRequest(3, 1, null, null));
        resultadoService.aceptar(perdedores.getFirst(), laFinal.id());
        entityManager.flush();

        insigniaService.evaluarResultadoConfirmado(torneoEq, laFinal.id(), true);
        entityManager.flush();
        entityManager.clear();

        for (Long ganador : ganadores) {
            assertThat(insigniaService.insigniasDe(ganador))
                    .extracting(i -> CodigoInsignia.valueOf(i.codigo()))
                    .containsExactlyInAnyOrder(
                            CodigoInsignia.PRIMER_TORNEO_JUGADO,
                            CodigoInsignia.PRIMER_PODIO,
                            CodigoInsignia.PRIMER_TORNEO_GANADO);
        }
        for (Long perdedor : perdedores) {
            assertThat(insigniaService.insigniasDe(perdedor))
                    .extracting(i -> CodigoInsignia.valueOf(i.codigo()))
                    .containsExactlyInAnyOrder(
                            CodigoInsignia.PRIMER_TORNEO_JUGADO,
                            CodigoInsignia.PRIMER_PODIO);
        }
        assertThat(insigniaService.insigniasDe(saleA)).isEmpty();
    }

    @Test
    void diezCheckinsSeguidosOtorganLaInsigniaYUnaAusenciaCortaLaRacha() {
        Long torneo = escenario.individualEnCurso(organizador, arbitro, "Copa Checkin",
                "jug_ck_" + sufijo() + "_", 2);
        Long partido1 = escenario.partido(torneo, 1, 1);
        Long jugador = escenario.jugador(partido1, "LOCAL");
        Long rival = escenario.jugador(partido1, "VISITANTE");
        Long inscripcionJugador = jdbc.queryForObject(
                "SELECT id FROM inscripcion WHERE torneo_id = ? AND usuario_id = ?",
                Long.class, torneo, jugador);
        Long inscripcionRival = jdbc.queryForObject(
                "SELECT id FROM inscripcion WHERE torneo_id = ? AND usuario_id = ?",
                Long.class, torneo, rival);

        // Primer check-in real desde CheckinService (publica CheckinHecho)
        escenario.empezadoAyer(torneo);
        torneoService.configurarCheckin(organizador, torneo, new CheckinTorneoRequest(30, 30));
        programacionService.programar(organizador, partido1, Instant.now().plus(5, ChronoUnit.MINUTES));
        checkinService.hacer(jugador, partido1);
        entityManager.flush();

        assertThat(eventos.stream(CheckinHecho.class))
                .anyMatch(e -> e.partidoId().equals(partido1) && e.inscripcionId().equals(inscripcionJugador));

        // Fechamos el primer check-in antes de la ausencia simulada para mantener la línea temporal
        jdbc.update("UPDATE juega SET checkin_en = now() - interval '60 hours' WHERE partido_id = ? AND inscripcion_id = ?",
                partido1, inscripcionJugador);

        // Siguientes 8 check-ins antiguos (9 en total antes de una ausencia): no alcanza la racha de 10
        for (int i = 1; i <= 8; i++) {
            insertarPartidoConCheckin(torneo, i + 1, inscripcionJugador, inscripcionRival,
                    "now() - interval '" + (50 - i) + " hours'");
        }
        insigniaService.evaluarCheckin(inscripcionJugador);
        assertThat(insigniaService.insigniasDe(jugador)).isEmpty();

        // Una ausencia por walkover corta la racha
        Long partidoAusencia = jdbc.queryForObject("""
                INSERT INTO partido (torneo_id, arbitro_id, ronda, posicion, estado, modificado_en)
                VALUES (?, ?, 99, 20, 'WALKOVER', now() - interval '30 hours') RETURNING id
                """, Long.class, torneo, arbitro);
        jdbc.update("""
                INSERT INTO juega (partido_id, inscripcion_id, lado, es_ganador, checkin_en)
                VALUES (?, ?, 'LOCAL', FALSE, NULL), (?, ?, 'VISITANTE', TRUE, now() - interval '30 hours')
                """, partidoAusencia, inscripcionJugador, partidoAusencia, inscripcionRival);
        entityManager.flush();

        // 9 check-ins posteriores a la ausencia: tampoco alcanza (la racha actual es de 9)
        for (int i = 1; i <= 9; i++) {
            insertarPartidoConCheckin(torneo, 30 + i, inscripcionJugador, inscripcionRival,
                    "now() - interval '" + (20 - i) + " hours'");
        }
        insigniaService.evaluarCheckin(inscripcionJugador);
        assertThat(insigniaService.insigniasDe(jugador))
                .noneMatch(i -> CodigoInsignia.DIEZ_CHECKINS_SEGUIDOS.name().equals(i.codigo()));

        // El 10.º check-in consecutivo posterior a la ausencia sí otorga la insignia
        insertarPartidoConCheckin(torneo, 50, inscripcionJugador, inscripcionRival,
                "now() + interval '1 minute'");
        insigniaService.evaluarCheckin(inscripcionJugador);
        entityManager.flush();
        entityManager.clear();

        assertThat(insigniaService.insigniasDe(jugador))
                .extracting(i -> CodigoInsignia.valueOf(i.codigo()))
                .containsExactly(CodigoInsignia.DIEZ_CHECKINS_SEGUIDOS);
    }

    @Test
    void otorgarEsIdempotenteEIgnoraNulos() {
        Long jugador = escenario.usuario("idem_" + sufijo(), "PARTICIPANTE").getId();

        assertThat(insigniaService.otorgar(null, CodigoInsignia.PRIMER_PODIO)).isFalse();
        assertThat(insigniaService.otorgar(jugador, null)).isFalse();
        assertThat(insigniaService.otorgar(jugador, CodigoInsignia.PRIMER_PODIO)).isTrue();
        assertThat(insigniaService.otorgar(jugador, CodigoInsignia.PRIMER_PODIO)).isFalse();

        entityManager.flush();
        entityManager.clear();
        assertThat(insigniaService.insigniasDe(jugador))
                .extracting(i -> CodigoInsignia.valueOf(i.codigo()))
                .containsExactly(CodigoInsignia.PRIMER_PODIO);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void alConfirmarUnaTransaccionRealElListenerAfterCommitPersisteLasInsignias() {
        String tag = "after_commit_" + sufijo();
        Long[] ids = transaccion.execute(status -> {
            long pOrg = jdbc.queryForObject(
                    "INSERT INTO persona (nombres, apellidos) VALUES (?, 'Test') RETURNING id",
                    Long.class, "org_" + tag);
            long uOrg = jdbc.queryForObject("""
                    INSERT INTO usuario (username, email, estado, email_verificado, persona_id)
                    VALUES (?, ?, 'ACTIVO', TRUE, ?) RETURNING id
                    """, Long.class, "org_" + tag, "org_" + tag + "@example.com", pOrg);
            long pJug = jdbc.queryForObject(
                    "INSERT INTO persona (nombres, apellidos) VALUES (?, 'Test') RETURNING id",
                    Long.class, "jug_" + tag);
            long uJug = jdbc.queryForObject("""
                    INSERT INTO usuario (username, email, estado, email_verificado, persona_id)
                    VALUES (?, ?, 'ACTIVO', TRUE, ?) RETURNING id
                    """, Long.class, "jug_" + tag, "jug_" + tag + "@example.com", pJug);
            long tId = jdbc.queryForObject("""
                    INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                        tipo_participante, cupo_maximo, cupo_minimo, valor_inscripcion,
                                        pct_gastos_administrativos, cierre_inscripcion_en, fecha_inicio, estado)
                    VALUES (?, 'Torneo Listener', 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE',
                            'INDIVIDUAL', 8, 2, 0, 0, now() - interval '7 days', now() - interval '1 day', 'FINALIZADO')
                    RETURNING id
                    """, Long.class, uOrg);
            long inscId = jdbc.queryForObject("""
                    INSERT INTO inscripcion (torneo_id, usuario_id, estado, posicion, reglamento_aceptado_en)
                    VALUES (?, ?, 'CONFIRMADA', 1, now() - interval '5 days') RETURNING id
                    """, Long.class, tId, uJug);

            publisher.publishEvent(new TorneoLiquidado(tId, null));
            return new Long[] {pOrg, uOrg, pJug, uJug, tId, inscId};
        });

        try {
            Long ganador = ids[3];
            assertThat(insigniaService.insigniasDe(ganador))
                    .extracting(i -> CodigoInsignia.valueOf(i.codigo()))
                    .containsExactlyInAnyOrder(
                            CodigoInsignia.PRIMER_TORNEO_GANADO,
                            CodigoInsignia.PRIMER_PODIO);
        } finally {
            jdbc.update("DELETE FROM usuario_insignia WHERE usuario_id = ?", ids[3]);
            jdbc.update("DELETE FROM inscripcion WHERE id = ?", ids[5]);
            jdbc.update("DELETE FROM torneo WHERE id = ?", ids[4]);
            jdbc.update("DELETE FROM usuario WHERE id IN (?, ?)", ids[1], ids[3]);
            jdbc.update("DELETE FROM persona WHERE id IN (?, ?)", ids[0], ids[2]);
        }
    }

    // --- helpers --------------------------------------------------------------

    private RifaDetalle crearYPublicarRifa(String titulo, Long torneoId, List<PremioRifaRequest> premios) {
        RifaDetalle creada = rifaService.crear(organizador, new RifaRequest(titulo, null, 1, 50,
                new BigDecimal("500"), MANIANA, EN_CINCO_DIAS, EN_SEIS_DIAS, torneoId, null));
        rifaService.reemplazarPremios(organizador, creada.id(), premios);
        rifaService.publicar(organizador, creada.id());
        jdbc.update("UPDATE rifa SET venta_desde = now() - interval '1 hour' WHERE id = ?", creada.id());
        entityManager.clear();
        return creada;
    }

    private void comprar(Long rifaId, Long comprador, int numero) {
        ventaRifaService.reservar(comprador, rifaId, List.of(numero));
        ventaRifaService.comprar(comprador, rifaId, List.of(numero), "compra-" + rifaId + "-" + numero);
        entityManager.flush();
    }

    private void comprarYSortear(Long rifaId, Long comprador, int numero) {
        comprar(rifaId, comprador, numero);
        jdbc.update("""
                UPDATE rifa SET venta_desde = now() - interval '3 hours',
                                venta_hasta = now() - interval '2 hours',
                                sorteada_en = now() - interval '1 hour'
                WHERE id = ?""", rifaId);
        entityManager.clear();
        cierreVentaService.cerrarSiVencio(rifaId, Instant.now().minus(90, ChronoUnit.MINUTES));
        sorteoRifaService.sortear(organizador, rifaId);
        entityManager.flush();
        entityManager.clear();
    }

    private void jugar(Long partidoId, int local, int visitante) {
        resultadoService.cargar(escenario.jugador(partidoId, "LOCAL"), partidoId,
                new ResultadoRequest(local, visitante, null, null));
        resultadoService.aceptar(escenario.jugador(partidoId, "VISITANTE"), partidoId);
        entityManager.flush();
    }

    private Long torneoPorEquipos() {
        Long id = torneoService.crear(organizador, new InformacionTorneoRequest("Copa Equipos Insignia", "Futbol",
                null, null, Modalidad.ONLINE, TipoParticipante.EQUIPO, null, null, EN_DOS_SEMANAS, null,
                EN_UNA_SEMANA, "Partidos de 20 minutos", 8, 2, 2, 2)).id();
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

    private Long inscribirEquipo(Long torneoId, Long capitan, String nombre, Long companiero) {
        Long id = inscripcionService.crear(capitan, torneoId,
                new InscripcionEquipoRequest(null, nombre, List.of(capitan, companiero))).id();
        inscripcionService.confirmar(capitan, id, true);
        return id;
    }

    private void insertarPartidoConCheckin(Long torneoId, int posicion, Long inscJugador, Long inscRival,
            String sqlFecha) {
        Long partidoId = jdbc.queryForObject("""
                INSERT INTO partido (torneo_id, arbitro_id, ronda, posicion, estado)
                VALUES (?, ?, 99, ?, 'CONFIRMADO') RETURNING id
                """, Long.class, torneoId, arbitro, posicion);
        jdbc.update(
                "INSERT INTO juega (partido_id, inscripcion_id, lado, checkin_en) VALUES (?, ?, 'LOCAL', "
                        + sqlFecha + "), (?, ?, 'VISITANTE', " + sqlFecha + ")",
                partidoId, inscJugador, partidoId, inscRival);
    }

    private static String sufijo() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
