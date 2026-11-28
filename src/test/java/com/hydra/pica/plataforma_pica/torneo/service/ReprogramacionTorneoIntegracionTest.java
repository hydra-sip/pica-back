package com.hydra.pica.plataforma_pica.torneo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.EscenarioTorneo;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.billetera.service.ControlDeBalance;
import com.hydra.pica.plataforma_pica.billetera.service.CustodiaService;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.inscripcion.service.PagoInscripcionService;
import com.hydra.pica.plataforma_pica.torneo.dto.ReprogramacionDetalle;
import com.hydra.pica.plataforma_pica.torneo.dto.ReprogramacionRequest;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.MapAssert;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * La reprogramación de un torneo (CR-01, PICA-391). Tres jugadores pagan $1000 más 10% de gastos administrativos en un
 * torneo de cupo 4 con la inscripción abierta. Cada test hace rollback al terminar.
 */
@SpringBootTest(properties = "app.cobro.modo=saldo")
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, EscenarioTorneo.class})
@Transactional
class ReprogramacionTorneoIntegracionTest {

    private static final Instant RECHAZO = Instant.now().plus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    private static final Instant CIERRE = Instant.now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    private static final Instant INICIO = Instant.now().plus(20, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    private static final Instant FIN = INICIO.plus(1, ChronoUnit.DAYS);

    @Autowired private ReprogramacionTorneoService reprogramacionService;
    @Autowired private VencimientoReprogramacion vencimiento;
    @Autowired private InscripcionTorneoService inscripcionTorneoService;
    @Autowired private PagoInscripcionService pagoInscripcionService;
    @Autowired private CustodiaService custodiaService;
    @Autowired private ControlDeBalance controlDeBalance;
    @Autowired private EscenarioTorneo escenario;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private Long organizador;
    private long torneo;
    private final long[] inscripcion = new long[4];
    private final Long[] jugador = new Long[4];

    @BeforeEach
    void escenario() {
        organizador = escenario.usuario("org_reprog", "ORGANIZADOR").getId();
        torneo = torneo();
        for (int i = 0; i < 4; i++) {
            jugador[i] = escenario.usuario("jug_reprog_" + i, "PARTICIPANTE").getId();
            saldo(jugador[i]);
            inscripcion[i] = inscripcion(jugador[i]);
        }
        for (int i = 0; i < 3; i++) {
            pagoInscripcionService.pagar(jugador[i], inscripcion[i]);
        }
        entityManager.flush();
    }

    // --- reprogramar --------------------------------------------------------------------------------

    @Test
    void cambiaLasFechasYLaPlataSigueEnCustodia() {
        BigDecimal antes = custodiaService.recaudadoDelTorneo(torneo);

        reprogramar();

        entityManager.flush();
        assertThat(custodiaService.recaudadoDelTorneo(torneo)).isEqualByComparingTo(antes);
        List<Long> custodias = jdbc.queryForList("SELECT id FROM custodia WHERE torneo_id = ?", Long.class, torneo);
        assertThat(controlDeBalance.custodias(custodias)).isEmpty();
        assertThat(jdbc.queryForMap("SELECT estado, fecha_inicio, fecha_fin, cierre_inscripcion_en FROM torneo "
                + "WHERE id = ?", torneo))
                .containsEntry("estado", "INSCRIPCION_ABIERTA")
                .hasEntrySatisfying("fecha_inicio", f -> assertThat(instante(f)).isEqualTo(INICIO))
                .hasEntrySatisfying("fecha_fin", f -> assertThat(instante(f)).isEqualTo(FIN))
                .hasEntrySatisfying("cierre_inscripcion_en", f -> assertThat(instante(f)).isEqualTo(CIERRE));
        assertThat(jdbc.queryForList("SELECT estado FROM inscripcion WHERE id IN (?, ?, ?)", String.class,
                inscripcion[0], inscripcion[1], inscripcion[2])).containsOnly("CONFIRMADA");
    }

    @Test
    void quedaEnElHistorialPublicoYEnLaAuditoria() {
        ReprogramacionDetalle hecha = reprogramar();

        assertThat(reprogramacionService.historial(torneo)).singleElement().satisfies(r -> {
            assertThat(r.id()).isEqualTo(hecha.id());
            assertThat(r.fechaInicioNueva()).isEqualTo(INICIO);
            assertThat(r.rechazoHasta()).isEqualTo(RECHAZO);
            assertThat(r.motivo()).isEqualTo("Lluvia");
            assertThat(r.plazoAbierto()).isTrue();
        });
        Map<String, Object> auditoria = jdbc.queryForMap(
                "SELECT usuario_id, entidad_id, detalle::text AS detalle FROM auditoria WHERE accion = 'REPROGRAMACION'");
        assertThat(auditoria).containsEntry("usuario_id", organizador).containsEntry("entidad_id", String.valueOf(torneo));
        assertThat((String) auditoria.get("detalle")).contains("\"motivo\": \"Lluvia\"", "INSCRIPCION_ABIERTA");
    }

    @Test
    void conLaInscripcionCerradaQuedaCerradaYSinLaListaSellada() {
        jdbc.update("UPDATE torneo SET estado = 'INSCRIPCION_CERRADA', lista_hash = repeat('a', 64), "
                + "lista_sellada_en = now() WHERE id = ?", torneo);
        entityManager.clear();

        reprogramar();

        entityManager.flush();
        assertThat(jdbc.queryForMap("SELECT estado, lista_hash, lista_sellada_en FROM torneo WHERE id = ?", torneo))
                .containsEntry("estado", "INSCRIPCION_CERRADA")
                .containsEntry("lista_hash", null)
                .containsEntry("lista_sellada_en", null);
    }

    @Test
    void enCursoSinResultadosSeDescartaLaLlaveConSusPronosticos() {
        Long otroOrganizador = escenario.usuario("org_reprog_curso", "ORGANIZADOR").getId();
        Long arbitro = escenario.usuario("arb_reprog_curso", "ARBITRO").getId();
        Long enCurso = escenario.individualEnCurso(otroOrganizador, arbitro, "En curso", "jug_reprog_curso_", 4);
        Long partido = escenario.partido(enCurso, 1, 1);
        jdbc.update("INSERT INTO pronostico (usuario_id, partido_id, eleccion) VALUES (?, ?, '1')", jugador[0], partido);

        reprogramacionService.reprogramar(otroOrganizador, enCurso, request());

        entityManager.flush();
        assertThat(jdbc.queryForMap("SELECT estado, semilla_bracket FROM torneo WHERE id = ?", enCurso))
                .containsEntry("estado", "INSCRIPCION_CERRADA")
                .containsEntry("semilla_bracket", null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM partido WHERE torneo_id = ?", Long.class, enCurso))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pronostico WHERE partido_id = ?", Long.class, partido))
                .isZero();
    }

    @Test
    void conResultadosCargadosNoSeReprograma() {
        Long otroOrganizador = escenario.usuario("org_reprog_res", "ORGANIZADOR").getId();
        Long arbitro = escenario.usuario("arb_reprog_res", "ARBITRO").getId();
        Long enCurso = escenario.individualEnCurso(otroOrganizador, arbitro, "Con resultados", "jug_reprog_res_", 4);
        jdbc.update("UPDATE juega SET puntaje = 1 WHERE partido_id = ?", escenario.partido(enCurso, 1, 1));
        entityManager.clear();

        assertError(() -> reprogramacionService.reprogramar(otroOrganizador, enCurso, request()),
                HttpStatus.CONFLICT, CodigoError.BRACKET_CON_RESULTADOS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BORRADOR", "FINALIZADO", "CANCELADO", "CANCELACION_EN_REVISION"})
    void soloSeReprogramaConLaInscripcionAbiertaOCerradaOEnCurso(String estado) {
        String motivo = estado.startsWith("CANCELA") ? "Lluvia" : null;
        jdbc.update("UPDATE torneo SET estado = ?, motivo_cancelacion = ? WHERE id = ?", estado, motivo, torneo);
        entityManager.clear();

        assertError(this::reprogramar, HttpStatus.CONFLICT, CodigoError.TORNEO_NO_REPROGRAMABLE);
    }

    @Test
    void conElPlazoAbiertoNoSeReprogramaOtraVez() {
        reprogramar();

        assertError(this::reprogramar, HttpStatus.CONFLICT, CodigoError.REPROGRAMACION_EN_CURSO);
    }

    @Test
    void otroUsuarioNoEncuentraElTorneo() {
        Long otro = escenario.usuario("org_reprog_otro", "ORGANIZADOR").getId();

        assertError(() -> reprogramacionService.reprogramar(otro, torneo, request()),
                HttpStatus.NOT_FOUND, CodigoError.TORNEO_NO_ENCONTRADO);
    }

    // --- validación ---------------------------------------------------------------------------------

    @Test
    void elMotivoYLasFechasSonObligatorios() {
        assertThatValidacion(new ReprogramacionRequest(null, null, null, null, " "))
                .containsEntry("motivo", ErrorCampo.Codigo.REQUERIDO)
                .containsEntry("fechaInicio", ErrorCampo.Codigo.REQUERIDO)
                .containsEntry("cierreInscripcion", ErrorCampo.Codigo.REQUERIDO)
                .containsEntry("rechazoHasta", ErrorCampo.Codigo.REQUERIDO);
    }

    @Test
    void elPlazoNoPuedeSerPasadoYLaInscripcionCierraDespuesDelPlazo() {
        Instant ayer = Instant.now().minus(1, ChronoUnit.DAYS);

        assertThatValidacion(new ReprogramacionRequest(INICIO, FIN, CIERRE, ayer, "Lluvia"))
                .containsEntry("rechazoHasta", ErrorCampo.Codigo.FECHAS_INVALIDAS);
        assertThatValidacion(new ReprogramacionRequest(INICIO, FIN, RECHAZO, RECHAZO, "Lluvia"))
                .containsEntry("cierreInscripcion", ErrorCampo.Codigo.FECHAS_INVALIDAS);
    }

    @Test
    void elFinNoPuedeSerAnteriorAlInicioYElCierreRespetaElMargen() {
        assertThatValidacion(new ReprogramacionRequest(INICIO, INICIO.minus(1, ChronoUnit.HOURS), CIERRE, RECHAZO,
                "Lluvia")).containsEntry("fechaFin", ErrorCampo.Codigo.FECHAS_INVALIDAS);
        assertThatValidacion(new ReprogramacionRequest(CIERRE, null, CIERRE, RECHAZO, "Lluvia"))
                .containsEntry("cierreInscripcion", ErrorCampo.Codigo.FECHAS_INVALIDAS);
    }

    // --- mientras corre el plazo --------------------------------------------------------------------

    @Test
    void completarElCupoNoCierraLaInscripcion() {
        reprogramar();

        pagoInscripcionService.pagar(jugador[3], inscripcion[3]);

        entityManager.flush();
        assertThat(estado()).isEqualTo("INSCRIPCION_ABIERTA");
    }

    @Test
    void elOrganizadorNoCierraLaInscripcionAMano() {
        reprogramar();

        assertError(() -> inscripcionTorneoService.cerrar(organizador, torneo), HttpStatus.CONFLICT,
                CodigoError.REPROGRAMACION_EN_CURSO);
    }

    // --- al vencer el plazo -------------------------------------------------------------------------

    @Test
    void conLugaresLibresLaInscripcionSeAbreHastaElNuevoCierre() {
        jdbc.update("UPDATE torneo SET estado = 'INSCRIPCION_CERRADA' WHERE id = ?", torneo);
        entityManager.clear();
        reprogramar();
        vencerElPlazo();

        assertThat(vencimiento.resolverVencidas()).isEqualTo(1);

        entityManager.flush();
        assertThat(estado()).isEqualTo("INSCRIPCION_ABIERTA");
        assertThat(reprogramacionService.historial(torneo)).singleElement()
                .satisfies(r -> assertThat(r.plazoAbierto()).isFalse());
    }

    @Test
    void conElCupoCompletoSeCierraYSeVuelveASellarLaLista() {
        reprogramar();
        pagoInscripcionService.pagar(jugador[3], inscripcion[3]);
        vencerElPlazo();

        assertThat(vencimiento.resolverVencidas()).isEqualTo(1);

        entityManager.flush();
        assertThat(jdbc.queryForMap("SELECT estado, lista_hash FROM torneo WHERE id = ?", torneo))
                .containsEntry("estado", "INSCRIPCION_CERRADA")
                .hasEntrySatisfying("lista_hash", h -> assertThat(h).isNotNull());
    }

    @Test
    void unPlazoQueNoVencioNoSeResuelve() {
        reprogramar();

        assertThat(vencimiento.resolverVencidas()).isZero();
        assertThat(reprogramacionService.historial(torneo)).singleElement()
                .satisfies(r -> assertThat(r.plazoAbierto()).isTrue());
    }

    @Test
    void conElPlazoResueltoSePuedeReprogramarDeNuevo() {
        reprogramar();
        vencerElPlazo();
        vencimiento.resolverVencidas();

        reprogramar();

        assertThat(reprogramacionService.historial(torneo)).hasSize(2);
    }

    @Test
    void elHistorialDeUnBorradorNoExiste() {
        jdbc.update("UPDATE torneo SET estado = 'BORRADOR' WHERE id = ?", torneo);
        entityManager.clear();

        assertError(() -> reprogramacionService.historial(torneo), HttpStatus.NOT_FOUND,
                CodigoError.TORNEO_NO_ENCONTRADO);
    }

    // --- ayudas -------------------------------------------------------------------------------------

    private static ReprogramacionRequest request() {
        return new ReprogramacionRequest(INICIO, FIN, CIERRE, RECHAZO, "Lluvia");
    }

    private ReprogramacionDetalle reprogramar() {
        return reprogramacionService.reprogramar(organizador, torneo, request());
    }

    private void vencerElPlazo() {
        entityManager.flush();
        jdbc.update("UPDATE reprogramacion SET rechazo_hasta = now() - interval '1 minute' WHERE torneo_id = ? "
                + "AND plazo_procesado_en IS NULL", torneo);
        entityManager.clear();
    }

    private String estado() {
        return jdbc.queryForObject("SELECT estado FROM torneo WHERE id = ?", String.class, torneo);
    }

    private static Instant instante(Object valor) {
        return ((Timestamp) valor).toInstant();
    }

    /** Los errores del 400 como campo → código. */
    @SuppressWarnings("unchecked")
    private MapAssert<String, ErrorCampo.Codigo> assertThatValidacion(ReprogramacionRequest request) {
        Throwable error = catchThrowable(() -> reprogramacionService.reprogramar(organizador, torneo, request));
        assertThat(error).isInstanceOf(ApiException.class);
        ApiException api = (ApiException) error;
        assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(api.getCodigo()).isEqualTo(CodigoError.VALIDACION);
        Map<String, ErrorCampo.Codigo> porCampo = new LinkedHashMap<>();
        ((List<ErrorCampo>) api.getPropiedades().get("errores")).forEach(e -> porCampo.putIfAbsent(e.campo(), e.codigo()));
        return assertThat(porCampo);
    }

    private static void assertError(ThrowingCallable accion, HttpStatus status, CodigoError codigo) {
        assertThatThrownBy(accion).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCodigo()).isEqualTo(codigo);
        });
    }

    private long torneo() {
        long id = jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, valor_inscripcion,
                                    pct_gastos_administrativos, cierre_inscripcion_en, fecha_inicio, estado,
                                    publicado_en)
                VALUES (?, 'Torneo a reprogramar', 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE',
                        'INDIVIDUAL', 4, 2, 1000, 10, now() + interval '1 day', now() + interval '7 days',
                        'INSCRIPCION_ABIERTA', now())
                RETURNING id
                """, Long.class, organizador);
        jdbc.update("UPDATE torneo SET slug = ? WHERE id = ?", "reprog-" + id, id);
        return id;
    }

    private long inscripcion(Long usuario) {
        return jdbc.queryForObject("""
                INSERT INTO inscripcion (torneo_id, usuario_id, estado, reglamento_aceptado_en)
                VALUES (?, ?, 'PENDIENTE_PAGO', now()) RETURNING id
                """, Long.class, torneo, usuario);
    }

    private void saldo(Long usuario) {
        jdbc.update("""
                INSERT INTO billetera (usuario_id, saldo_disponible) VALUES (?, 5000)
                ON CONFLICT (usuario_id) DO UPDATE SET saldo_disponible = excluded.saldo_disponible
                """, usuario);
        entityManager.clear();
    }
}
