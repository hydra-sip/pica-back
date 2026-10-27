package com.hydra.pica.plataforma_pica.partido.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.EscenarioTorneo;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.partido.dto.EventoPartido;
import com.hydra.pica.plataforma_pica.partido.dto.ObjecionRequest;
import com.hydra.pica.plataforma_pica.partido.dto.PartidoDetalle;
import com.hydra.pica.plataforma_pica.partido.dto.ResultadoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.CheckinTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoInformacion;
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
 * Programar los partidos y la ventana de check-in (HU-308, PICA-250) contra Postgres: la fecha del partido, la
 * configuración del torneo, la ventana con su tolerancia y el check-in de cada capitán. Un torneo individual de 4 en
 * curso; las ventanas se arman con fechas relativas a ahora. Cada test hace rollback al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, EscenarioTorneo.class})
@Transactional
class CheckinIntegracionTest {

    /** Después del inicio del torneo, que el escenario pone dentro de dos semanas. */
    private static final byte[] JPG = Arrays.copyOf(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0},
            2048);
    private static final Instant FECHA = Instant.now().plus(20, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES);

    @Autowired private ProgramacionService programacionService;
    @Autowired private ResultadoService resultadoService;
    @Autowired private TorneoService torneoService;
    @Autowired private EvidenciaService evidenciaService;
    @Autowired private ObjecionService objecionService;
    @MockitoBean private EmailService emailService;
    @Autowired private EscenarioTorneo escenario;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private Long organizador;
    private Long arbitro;
    private Long torneo;
    private Long partido;

    @BeforeEach
    void enCurso() {
        organizador = escenario.usuario("org_checkin", "ORGANIZADOR").getId();
        arbitro = escenario.usuario("juez_checkin", "ARBITRO").getId();
        torneo = escenario.individualEnCurso(organizador, arbitro, "Copa Check-in", "jugador_checkin_", 4);
        partido = escenario.partido(torneo, 1, 1);
    }

    // --- programar el partido -------------------------------------------------

    @Test
    void elOrganizadorLePoneFechaAlPartidoYQuedaEnLaAuditoria() {
        Instant fecha = Instant.parse("2031-03-10T18:00:00Z");

        PartidoDetalle programado = programacionService.programar(organizador, partido, fecha);

        assertThat(programado.fechaProgramada()).isEqualTo(fecha);
        entityManager.flush();
        assertThat(jdbc.queryForMap("""
                SELECT usuario_id, detalle ->> 'resultado' AS resultado, detalle ->> 'fechaProgramada' AS fecha
                FROM auditoria WHERE accion = 'MODIFICACION' AND entidad = 'PARTIDO' AND entidad_id = ?""",
                String.valueOf(partido)))
                .isEqualTo(Map.of("usuario_id", organizador, "resultado", "PROGRAMADO",
                        "fecha", "2031-03-10T18:00:00Z"));
    }

    @Test
    void soloElOrganizadorProgramaYParaLosDemasElPartidoNoExiste() {
        for (Long ajeno : List.of(escenario.jugador(partido, "LOCAL"), arbitro)) {
            assertThatThrownBy(() -> programacionService.programar(ajeno, partido, FECHA))
                    .extracting("codigo").isEqualTo(CodigoError.PARTIDO_NO_ENCONTRADO);
        }
    }

    @Test
    void seProgramaConElPartidoProgramadoAunqueFalteElRivalYSePuedeReprogramar() {
        Long laFinal = escenario.partido(torneo, 2, 1);

        programacionService.programar(organizador, laFinal, FECHA);
        PartidoDetalle reprogramado =
                programacionService.programar(organizador, laFinal, FECHA.plus(1, ChronoUnit.DAYS));

        assertThat(reprogramado.fechaProgramada()).isEqualTo(FECHA.plus(1, ChronoUnit.DAYS));
        assertThat(reprogramado.visitante()).isNull();
    }

    @Test
    void unPartidoQueYaTieneResultadoOUnTorneoTerminadoNoSePrograma() {
        resultadoService.cargar(escenario.jugador(partido, "LOCAL"), partido, new ResultadoRequest(2, 0, null, null));
        entityManager.flush();

        assertThatThrownBy(() -> programacionService.programar(organizador, partido, FECHA))
                .extracting("codigo").isEqualTo(CodigoError.PARTIDO_NO_PROGRAMABLE);

        Long otro = escenario.partido(torneo, 1, 2);
        jdbc.update("UPDATE torneo SET estado = 'FINALIZADO' WHERE id = ?", torneo);
        entityManager.clear();
        assertThatThrownBy(() -> programacionService.programar(organizador, otro, FECHA))
                .extracting("codigo").isEqualTo(CodigoError.PARTIDO_NO_PROGRAMABLE);
    }

    @Test
    void laFechaEsObligatoriaFuturaYDentroDelTorneo() {
        Instant inicio = jdbc.queryForObject("SELECT fecha_inicio FROM torneo WHERE id = ?", Instant.class, torneo);
        jdbc.update("UPDATE torneo SET fecha_fin = ? WHERE id = ?",
                Timestamp.from(inicio.plus(30, ChronoUnit.DAYS)), torneo);
        entityManager.clear();

        for (Instant invalida : Arrays.asList(null, Instant.now().minus(1, ChronoUnit.HOURS),
                inicio.minus(1, ChronoUnit.MINUTES), inicio.plus(31, ChronoUnit.DAYS))) {
            ErrorCampo.Codigo esperado =
                    invalida == null ? ErrorCampo.Codigo.REQUERIDO : ErrorCampo.Codigo.RANGO_INVALIDO;
            assertThatThrownBy(() -> programacionService.programar(organizador, partido, invalida))
                    .satisfies(e -> assertThat(errores(e)).extracting(ErrorCampo::campo, ErrorCampo::codigo)
                            .containsExactly(tuple("fechaProgramada", esperado)));
        }
        assertThat(programacionService.programar(organizador, partido, inicio).fechaProgramada()).isEqualTo(inicio);
    }

    @Test
    void laProgramacionApareceEnElHistorialDelPartido() {
        programacionService.programar(organizador, partido, FECHA);
        Long local = escenario.jugador(partido, "LOCAL");
        Long visitante = escenario.jugador(partido, "VISITANTE");
        resultadoService.cargar(local, partido, new ResultadoRequest(2, 0, null, null));
        evidenciaService.subir(visitante, partido, JPG, "http://localhost/api/v1/partidos/1/evidencias");
        resultadoService.objetar(visitante, partido, new ObjecionRequest("No fue así", 0, 1, null, null));
        entityManager.flush();
        entityManager.clear();

        List<EventoPartido> historial = objecionService.comparar(organizador, partido).historial();

        assertThat(historial).extracting(EventoPartido::evento).containsExactly("PROGRAMADO", "CARGADO", "OBJETADO");
        assertThat(historial.getFirst()).extracting(EventoPartido::usuario, EventoPartido::lado)
                .containsExactly("org_checkin Test", null);
    }

    // --- configurar la ventana del torneo -----------------------------------------

    @Test
    void elOrganizadorConfiguraLaVentanaYQuedaEnElTorneoYEnLaAuditoria() {
        TorneoInformacion torneoConVentana =
                torneoService.configurarCheckin(organizador, torneo, new CheckinTorneoRequest(30, 20));

        assertThat(torneoConVentana).extracting(TorneoInformacion::checkinMinAntes, TorneoInformacion::checkinDuracion)
                .containsExactly(30, 20);
        entityManager.flush();
        assertThat(jdbc.queryForMap("""
                SELECT usuario_id, detalle ->> 'minAntes' AS min_antes, detalle ->> 'duracion' AS duracion
                FROM auditoria WHERE accion = 'MODIFICACION' AND entidad = 'TORNEO' AND entidad_id = ?""",
                String.valueOf(torneo)))
                .isEqualTo(Map.of("usuario_id", organizador, "min_antes", "30", "duracion", "20"));
    }

    @Test
    void unaDuracionNoPositivaOUnaVentanaQueLlegaAlCruceSeRechazan() {
        Map<CheckinTorneoRequest, List<ErrorCampo.Codigo>> invalidas = new LinkedHashMap<>();
        invalidas.put(new CheckinTorneoRequest(30, 0), List.of(ErrorCampo.Codigo.RANGO_INVALIDO));
        invalidas.put(new CheckinTorneoRequest(30, -5), List.of(ErrorCampo.Codigo.RANGO_INVALIDO));
        invalidas.put(new CheckinTorneoRequest(0, 10), List.of(ErrorCampo.Codigo.RANGO_INVALIDO));
        invalidas.put(new CheckinTorneoRequest(30, 31), List.of(ErrorCampo.Codigo.VENTANA_INVALIDA));
        invalidas.put(new CheckinTorneoRequest(null, null),
                List.of(ErrorCampo.Codigo.REQUERIDO, ErrorCampo.Codigo.REQUERIDO));

        invalidas.forEach((request, codigos) ->
                assertThatThrownBy(() -> torneoService.configurarCheckin(organizador, torneo, request))
                        .satisfies(e -> assertThat(errores(e)).extracting(ErrorCampo::codigo)
                                .containsExactlyElementsOf(codigos)));
        assertThat(torneoService.configurarCheckin(organizador, torneo, new CheckinTorneoRequest(30, 30))
                .checkinDuracion()).isEqualTo(30);
    }

    @Test
    void soloElOrganizadorConfiguraYSoloConElTorneoPublicadoSinTerminar() {
        assertThatThrownBy(() -> torneoService.configurarCheckin(arbitro, torneo, new CheckinTorneoRequest(30, 20)))
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_ENCONTRADO);

        for (String estado : List.of("BORRADOR", "FINALIZADO", "CANCELADO")) {
            jdbc.update("UPDATE torneo SET estado = ?, motivo_cancelacion = 'Lluvia' WHERE id = ?", estado, torneo);
            entityManager.clear();
            assertThatThrownBy(() -> torneoService.configurarCheckin(organizador, torneo,
                    new CheckinTorneoRequest(30, 20)))
                    .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_EDITABLE);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ErrorCampo> errores(Throwable e) {
        return (List<ErrorCampo>) ((ApiException) e).getPropiedades().get("errores");
    }
}
