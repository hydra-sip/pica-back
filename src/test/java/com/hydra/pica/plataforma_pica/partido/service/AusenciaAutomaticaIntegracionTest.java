package com.hydra.pica.plataforma_pica.partido.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.EscenarioTorneo;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.dto.LadoBracket;
import com.hydra.pica.plataforma_pica.partido.dto.ObjecionRequest;
import com.hydra.pica.plataforma_pica.partido.dto.PartidoDetalle;
import com.hydra.pica.plataforma_pica.partido.dto.PronosticoRequest;
import com.hydra.pica.plataforma_pica.partido.dto.ResultadoRequest;
import com.hydra.pica.plataforma_pica.partido.event.ResultadoConfirmado;
import com.hydra.pica.plataforma_pica.torneo.dto.CheckinTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.service.TorneoService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * La victoria por ausencia al vencer la ventana de check-in (HU-309, PICA-252) contra Postgres, llamando a mano a
 * {@link AusenciaAutomatica#declararVencidas()}. Un torneo individual de 4 en curso que empezó ayer; las ventanas se
 * arman con fechas relativas a ahora. Cada test hace rollback al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, EscenarioTorneo.class})
@Transactional
class AusenciaAutomaticaIntegracionTest {

    @Autowired private AusenciaAutomatica ausenciaAutomatica;
    @Autowired private CheckinService checkinService;
    @Autowired private ProgramacionService programacionService;
    @Autowired private PartidoService partidoService;
    @Autowired private TorneoService torneoService;
    @Autowired private ResultadoService resultadoService;
    @Autowired private PronosticoService pronosticoService;
    @Autowired private AvisoAusencia avisoAusencia;
    @MockitoBean private EmailService emailService;
    @Autowired private EscenarioTorneo escenario;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private Long organizador;
    private Long torneo;
    /** El primer partido de la primera ronda. */
    private Long partido;

    @BeforeEach
    void enCurso() {
        organizador = escenario.usuario("org_ausencia", "ORGANIZADOR").getId();
        Long arbitro = escenario.usuario("juez_ausencia", "ARBITRO").getId();
        torneo = escenario.individualEnCurso(organizador, arbitro, "Copa Ausencia", "jugador_ausencia_", 4);
        escenario.empezadoAyer(torneo);
        partido = escenario.partido(torneo, 1, 1);
    }

    // --- un solo presente ---------------------------------------------------------

    @Test
    void conUnSoloPresenteGanaPorAusenciaYPasaALaRondaSiguiente() {
        Long inscripcionLocal = inscripcion(partido, "LOCAL");
        vencerLaVentana(partido, "LOCAL");

        assertThat(ausenciaAutomatica.declararVencidas()).isEqualTo(1);

        PartidoDetalle walkover = ver(partido);
        assertThat(walkover.estado()).isEqualTo(EstadoPartido.WALKOVER);
        assertThat(walkover.objecionVenceEn()).isNull();
        assertThat(walkover.local()).extracting(LadoBracket::puntaje, LadoBracket::ganador).containsExactly(null, true);
        assertThat(walkover.visitante()).extracting(LadoBracket::puntaje, LadoBracket::ganador)
                .containsExactly(null, false);
        assertThat(ver(escenario.partido(torneo, 2, 1)).local().inscripcionId()).isEqualTo(inscripcionLocal);
    }

    @Test
    void unWalkoverEnLaFinalTerminaElTorneoConSusPosiciones() {
        Long laFinal = llegarALaFinal();
        Long campeon = inscripcion(laFinal, "VISITANTE");
        Long finalista = inscripcion(laFinal, "LOCAL");
        vencerLaVentana(laFinal, "VISITANTE");

        assertThat(ausenciaAutomatica.declararVencidas()).isEqualTo(1);

        assertThat(ver(laFinal).estado()).isEqualTo(EstadoPartido.WALKOVER);
        assertThat(jdbc.queryForObject("SELECT estado FROM torneo WHERE id = ?", String.class, torneo))
                .isEqualTo("FINALIZADO");
        assertThat(jdbc.queryForList("SELECT posicion FROM inscripcion WHERE torneo_id = ? ORDER BY posicion",
                Integer.class, torneo)).containsExactly(1, 2, 3, 3);
        assertThat(posicion(campeon)).isEqualTo(1);
        assertThat(posicion(finalista)).isEqualTo(2);
    }

    @Test
    void unWalkoverNoSePuedeObjetar() {
        vencerLaVentana(partido, "VISITANTE");
        ausenciaAutomatica.declararVencidas();

        assertThatThrownBy(() -> resultadoService.objetar(escenario.jugador(partido, "LOCAL"), partido,
                new ObjecionRequest("Llegué tarde", 1, 0, null, null)))
                .extracting("codigo").isEqualTo(CodigoError.RESULTADO_NO_PENDIENTE);
    }

    @Test
    void losPronosticosDeUnWalkoverSePuntuanSegunQuienGano() {
        Long acierta = escenario.usuario("acierta_ausencia", "PARTICIPANTE").getId();
        Long falla = escenario.usuario("falla_ausencia", "PARTICIPANTE").getId();
        abrirLaVentana(partido);
        pronosticoService.pronosticar(acierta, partido, new PronosticoRequest("1"));
        pronosticoService.pronosticar(falla, partido, new PronosticoRequest("2"));
        checkinService.hacer(escenario.jugador(partido, "LOCAL"), partido);
        cerrarLaVentana();

        ausenciaAutomatica.declararVencidas();

        assertThat(puntos(acierta, partido)).isEqualTo(3);
        assertThat(puntos(falla, partido)).isZero();
    }

    @Test
    void elWalkoverQuedaEnLaAuditoriaHechoPorElSistemaConElLadoAusente() {
        Long ausente = inscripcion(partido, "VISITANTE");
        vencerLaVentana(partido, "LOCAL");

        ausenciaAutomatica.declararVencidas();
        entityManager.flush();

        assertThat(jdbc.queryForList("""
                SELECT usuario_id IS NULL AS sistema, detalle ->> 'lado' AS lado,
                       (detalle ->> 'inscripcionId')::bigint AS inscripcion
                FROM auditoria WHERE accion = 'MODIFICACION' AND entidad = 'PARTIDO' AND entidad_id = ?
                  AND detalle ->> 'resultado' = 'WALKOVER'""", String.valueOf(partido)))
                .containsExactly(Map.of("sistema", true, "lado", "VISITANTE", "inscripcion", ausente));
    }

    @Test
    void delWalkoverSeAvisaALosDosCapitanesYAlOrganizadorAunqueUnMailFalle() {
        vencerLaVentana(partido, "LOCAL");
        String ganador = nombre(partido, "LOCAL");
        String perdedor = nombre(partido, "VISITANTE");
        ausenciaAutomatica.declararVencidas();
        doThrow(new IllegalStateException("SMTP caído")).when(emailService)
                .enviarAviso(eq(email(partido, "LOCAL")), any(), any());

        avisoAusencia.avisar(new ResultadoConfirmado(torneo, partido, inscripcion(partido, "LOCAL"), false));

        for (String email : List.of(email(partido, "LOCAL"), email(partido, "VISITANTE"), "org_ausencia@example.com")) {
            verify(emailService).enviarAviso(eq(email), eq("Victoria por ausencia en Copa Ausencia"),
                    contains(ganador + " ganó por ausencia de " + perdedor));
        }
        verifyNoMoreInteractions(emailService);
        assertThat(ver(partido).estado()).isEqualTo(EstadoPartido.WALKOVER);
    }

    @Test
    void unResultadoConfirmadoQueNoEsWalkoverNoSeAvisaComoAusencia() {
        resultadoService.cargar(escenario.jugador(partido, "LOCAL"), partido, new ResultadoRequest(2, 0, null, null));
        resultadoService.aceptar(escenario.jugador(partido, "VISITANTE"), partido);
        entityManager.flush();

        avisoAusencia.avisar(new ResultadoConfirmado(torneo, partido, inscripcion(partido, "LOCAL"), false));

        verifyNoInteractions(emailService);
    }

    // --- lo que la tarea saltea ------------------------------------------------------

    @Test
    void salteaLosDosPresentesElResultadoCargadoSinFechaSinRivalYDentroDeLaTolerancia() {
        vencerLaVentana(partido, "LOCAL", "VISITANTE"); // EN_JUEGO: estuvieron los dos
        Long conResultado = escenario.partido(torneo, 1, 2);
        programacionService.programar(organizador, conResultado, Instant.now().plus(5, ChronoUnit.MINUTES));
        resultadoService.cargar(escenario.jugador(conResultado, "LOCAL"), conResultado,
                new ResultadoRequest(1, 0, null, null));
        Long sinRival = escenario.partido(torneo, 2, 1);
        programacionService.programar(organizador, sinRival, Instant.now().plus(5, ChronoUnit.MINUTES));
        entityManager.flush();

        assertThat(ausenciaAutomatica.declararVencidas()).isZero();

        assertThat(ver(partido).estado()).isEqualTo(EstadoPartido.EN_JUEGO);
        assertThat(ver(conResultado).estado()).isEqualTo(EstadoPartido.RESULTADO_CARGADO);
        assertThat(ver(sinRival).estado()).isEqualTo(EstadoPartido.PROGRAMADO);
    }

    @Test
    void sinFechaOConLaVentanaCerradaHaceMenosQueLaToleranciaNoHayAusencia() {
        Long sinFecha = escenario.partido(torneo, 1, 2);
        jdbc.update("UPDATE partido SET fecha_programada = NULL WHERE id = ?", sinFecha);
        torneoService.configurarCheckin(organizador, torneo, new CheckinTorneoRequest(30, 20));
        // cierra 10 minutos antes del partido: cerró hace 1 minuto, dentro de los 2 de tolerancia
        programacionService.programar(organizador, partido, Instant.now().plus(9, ChronoUnit.MINUTES));
        entityManager.flush();

        assertThat(ausenciaAutomatica.declararVencidas()).isZero();

        assertThat(ver(partido).estado()).isEqualTo(EstadoPartido.PROGRAMADO);
        assertThat(ver(sinFecha).fechaProgramada()).isNull();
    }

    @Test
    void enUnTorneoSinCheckinConfiguradoNoHayAusencias() {
        jdbc.update("UPDATE partido SET fecha_programada = now() - interval '1 hour' WHERE id = ?", partido);
        entityManager.clear();

        assertThat(ausenciaAutomatica.declararVencidas()).isZero();
        assertThat(ver(partido).estado()).isEqualTo(EstadoPartido.PROGRAMADO);
    }

    /** Con la ventana abierta y los {@code presentes} con check-in, la cierra: ver {@link #cerrarLaVentana()}. */
    private void vencerLaVentana(Long partido, String... presentes) {
        abrirLaVentana(partido, presentes);
        cerrarLaVentana();
    }

    /** Programa el partido dentro de 5 minutos con una ventana de 30 y 30, abierta, y hacen check-in los presentes. */
    private void abrirLaVentana(Long partido, String... presentes) {
        torneoService.configurarCheckin(organizador, torneo, new CheckinTorneoRequest(30, 30));
        programacionService.programar(organizador, partido, Instant.now().plus(5, ChronoUnit.MINUTES));
        for (String lado : presentes) {
            checkinService.hacer(escenario.jugador(partido, lado), partido);
        }
    }

    /** La ventana se achica a 30 y 20: la de un partido dentro de 5 minutos cerró hace 5, 3 más allá de la tolerancia. */
    private void cerrarLaVentana() {
        torneoService.configurarCheckin(organizador, torneo, new CheckinTorneoRequest(30, 20));
        entityManager.flush();
        entityManager.clear();
    }

    /** Las semifinales con resultado aceptado: la final queda con los dos lados. */
    private Long llegarALaFinal() {
        for (int posicion = 1; posicion <= 2; posicion++) {
            Long semi = escenario.partido(torneo, 1, posicion);
            resultadoService.cargar(escenario.jugador(semi, "LOCAL"), semi, new ResultadoRequest(2, 0, null, null));
            resultadoService.aceptar(escenario.jugador(semi, "VISITANTE"), semi);
        }
        return escenario.partido(torneo, 2, 1);
    }

    private PartidoDetalle ver(Long partido) {
        entityManager.flush();
        entityManager.clear();
        return partidoService.ver(organizador, partido);
    }

    /** "{username} Test", como los arma el escenario. */
    private String nombre(Long partido, String lado) {
        return jdbc.queryForObject("SELECT username FROM usuario WHERE id = ?", String.class,
                escenario.jugador(partido, lado)) + " Test";
    }

    private String email(Long partido, String lado) {
        return jdbc.queryForObject("SELECT email FROM usuario WHERE id = ?", String.class,
                escenario.jugador(partido, lado));
    }

    private Integer posicion(Long inscripcion) {
        return jdbc.queryForObject("SELECT posicion FROM inscripcion WHERE id = ?", Integer.class, inscripcion);
    }

    private Integer puntos(Long usuario, Long partido) {
        entityManager.flush();
        return jdbc.queryForObject("SELECT puntos FROM pronostico WHERE usuario_id = ? AND partido_id = ?",
                Integer.class, usuario, partido);
    }

    private Long inscripcion(Long partido, String lado) {
        return jdbc.queryForObject("SELECT inscripcion_id FROM juega WHERE partido_id = ? AND lado = ?", Long.class,
                partido, lado);
    }
}
