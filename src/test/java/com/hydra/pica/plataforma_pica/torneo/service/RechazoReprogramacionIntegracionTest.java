package com.hydra.pica.plataforma_pica.torneo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.EscenarioTorneo;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoCustodia;
import com.hydra.pica.plataforma_pica.billetera.dto.ComprobantePago;
import com.hydra.pica.plataforma_pica.billetera.service.ComprobanteService;
import com.hydra.pica.plataforma_pica.billetera.service.ControlDeBalance;
import com.hydra.pica.plataforma_pica.billetera.service.CustodiaService;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.inscripcion.service.InscripcionService;
import com.hydra.pica.plataforma_pica.torneo.dto.RechazoReprogramacion;
import com.hydra.pica.plataforma_pica.torneo.dto.ReprogramacionRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.SolicitudCancelacion;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rechazar la nueva fecha de un torneo reprogramado (CR-01-3, PICA-392). Tres jugadores pagan $1000 más 10% de gastos
 * administrativos con saldo cargado ($5000) en un torneo de cupo 4 y mínimo 2, y el organizador lo reprograma. Cada
 * test hace rollback al terminar.
 */
@SpringBootTest(properties = "app.cobro.modo=saldo")
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, EscenarioTorneo.class})
@Transactional
class RechazoReprogramacionIntegracionTest {

    private static final Instant RECHAZO = Instant.now().plus(5, ChronoUnit.DAYS);
    private static final Instant CIERRE = Instant.now().plus(10, ChronoUnit.DAYS);
    private static final Instant INICIO = Instant.now().plus(20, ChronoUnit.DAYS);

    @Autowired private RechazoReprogramacionService rechazoService;
    @Autowired private ReprogramacionTorneoService reprogramacionService;
    @Autowired private CancelacionTorneoService cancelacionService;
    @Autowired private InscripcionService inscripcionService;
    @Autowired private CustodiaService custodiaService;
    @Autowired private ComprobanteService comprobanteService;
    @Autowired private ControlDeBalance controlDeBalance;
    @Autowired private EscenarioTorneo escenario;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private Long organizador;
    private long torneo;
    private final Long[] jugador = new Long[3];
    private final long[] inscripcion = new long[3];

    @BeforeEach
    void escenario() {
        organizador = escenario.usuario("org_rech", "ORGANIZADOR").getId();
        torneo = torneo();
        for (int i = 0; i < 3; i++) {
            jugador[i] = escenario.usuario("jug_rech_" + i, "PARTICIPANTE").getId();
            cargar(jugador[i]);
            inscripcion[i] = inscripcion(torneo, jugador[i]);
            inscripcionService.pagar(jugador[i], inscripcion[i]);
        }
        reprogramacionService.reprogramar(organizador, torneo,
                new ReprogramacionRequest(INICIO, null, CIERRE, RECHAZO, "Lluvia"));
        entityManager.flush();
    }

    @Test
    void dentroDelPlazoSeLeDevuelveLaInscripcionSinLosGastos() {
        RechazoReprogramacion rechazo = rechazoService.rechazar(jugador[0], inscripcion[0]);

        assertThat(rechazo.estado()).isEqualTo("REINTEGRADA");
        assertThat(rechazo.reintegrado()).isEqualByComparingTo("1000.00");
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT estado FROM inscripcion WHERE id = ?", String.class, inscripcion[0]))
                .isEqualTo("REINTEGRADA");
        // pagó 1100 de 5000: vuelven los 1000 de la inscripción; los 100 de gastos siguen retenidos en la custodia
        assertBilletera(jugador[0], "4900.00", "100.00");
        assertThat(custodiaService.recaudadoDelTorneo(torneo)).isEqualByComparingTo("2000.00");
    }

    @Test
    void elPagoQuedaRevertidoYElReintegroEsUnMovimientoNuevoQueCierraElBalance() {
        rechazoService.rechazar(jugador[0], inscripcion[0]);

        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT estado FROM movimiento WHERE inscripcion_id = ? AND tipo = 'INSCRIPCION'",
                String.class, inscripcion[0])).isEqualTo("REVERTIDO");
        Map<String, Object> reintegro = jdbc.queryForMap("""
                SELECT m.monto, m.torneo_id, m.custodia_id, m.referencia_externa FROM movimiento m
                WHERE m.inscripcion_id = ? AND m.tipo = 'REINTEGRO'""", inscripcion[0]);
        assertThat(reintegro).containsEntry("monto", new BigDecimal("1000.00"))
                .containsEntry("torneo_id", torneo)
                .containsEntry("custodia_id", null)
                .containsEntry("referencia_externa", "reint-t" + torneo + "-reprog-" + inscripcion[0]);
        assertBalanceCero();
    }

    @Test
    void liberaElLugarYQuedaEnLaAuditoria() {
        rechazoService.rechazar(jugador[0], inscripcion[0]);

        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inscripcion WHERE torneo_id = ? AND estado = 'CONFIRMADA'",
                Long.class, torneo)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auditoria WHERE accion = 'REINTEGRO' AND entidad_id = ?",
                Long.class, String.valueOf(torneo))).isEqualTo(1);
        assertThat(detalleDelRechazo(inscripcion[0])).contains("\"bajoElMinimo\": false", "\"reintegrado\": 1000.00");
        assertThat(reprogramacionService.historial(torneo)).singleElement()
                .satisfies(r -> assertThat(r.rechazos()).isEqualTo(1));
    }

    @Test
    void siQuedanMenosQueElMinimoSeAvisaPeroNoSeCancela() {
        rechazoService.rechazar(jugador[0], inscripcion[0]);
        rechazoService.rechazar(jugador[1], inscripcion[1]);

        entityManager.flush();
        assertThat(detalleDelRechazo(inscripcion[1])).contains("\"bajoElMinimo\": true");
        assertThat(jdbc.queryForObject("SELECT estado FROM torneo WHERE id = ?", String.class, torneo))
                .isEqualTo("INSCRIPCION_ABIERTA");
    }

    @Test
    void repetirElPedidoNoDevuelveDosVeces() {
        rechazoService.rechazar(jugador[0], inscripcion[0]);

        assertError(() -> rechazoService.rechazar(jugador[0], inscripcion[0]), HttpStatus.CONFLICT,
                CodigoError.INSCRIPCION_NO_EDITABLE);
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento WHERE inscripcion_id = ? AND tipo = 'REINTEGRO'",
                Long.class, inscripcion[0])).isEqualTo(1);
    }

    @Test
    void fueraDelPlazoNoSeRechaza() {
        jdbc.update("UPDATE reprogramacion SET rechazo_hasta = now() - interval '1 minute' WHERE torneo_id = ?",
                torneo);
        entityManager.clear();

        assertError(() -> rechazoService.rechazar(jugador[0], inscripcion[0]), HttpStatus.CONFLICT,
                CodigoError.PLAZO_RECHAZO_VENCIDO);
    }

    @Test
    void conElPlazoYaResueltoNoSeRechaza() {
        jdbc.update("UPDATE reprogramacion SET plazo_procesado_en = now() WHERE torneo_id = ?", torneo);
        entityManager.clear();

        assertError(() -> rechazoService.rechazar(jugador[0], inscripcion[0]), HttpStatus.CONFLICT,
                CodigoError.PLAZO_RECHAZO_VENCIDO);
    }

    @Test
    void quienSeInscribioConLaNuevaFechaYaPublicadaNoLaPuedeRechazar() {
        Long nuevo = escenario.usuario("jug_rech_nuevo", "PARTICIPANTE").getId();
        cargar(nuevo);
        long posterior = inscripcion(torneo, nuevo);
        jdbc.update("UPDATE inscripcion SET creado_en = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().plusSeconds(1)), posterior);
        entityManager.clear();
        inscripcionService.pagar(nuevo, posterior);

        assertError(() -> rechazoService.rechazar(nuevo, posterior), HttpStatus.CONFLICT,
                CodigoError.INSCRIPCION_POSTERIOR_A_REPROGRAMACION);
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT estado FROM inscripcion WHERE id = ?", String.class, posterior))
                .isEqualTo("CONFIRMADA");
    }

    @Test
    void otroUsuarioNoEncuentraLaInscripcion() {
        assertError(() -> rechazoService.rechazar(jugador[1], inscripcion[0]), HttpStatus.NOT_FOUND,
                CodigoError.INSCRIPCION_NO_ENCONTRADA);
    }

    @Test
    void enEquiposLoHaceElCapitanYUnJugadorRecibe403() {
        Long capitan = escenario.usuario("cap_rech", "PARTICIPANTE").getId();
        Long companero = escenario.usuario("comp_rech", "PARTICIPANTE").getId();
        cargar(capitan);
        long porEquipos = torneo();
        jdbc.update("UPDATE torneo SET tipo_participante = 'EQUIPO', tamanio_equipo = 2, nomina_maxima = 2 "
                + "WHERE id = ?", porEquipos);
        long equipo = jdbc.queryForObject("INSERT INTO equipo (capitan_id, nombre) VALUES (?, 'Los de siempre') "
                + "RETURNING id", Long.class, capitan);
        long deEquipo = jdbc.queryForObject("""
                INSERT INTO inscripcion (torneo_id, equipo_id, estado, reglamento_aceptado_en)
                VALUES (?, ?, 'PENDIENTE_PAGO', now()) RETURNING id
                """, Long.class, porEquipos, equipo);
        for (Long integrante : List.of(capitan, companero)) {
            jdbc.update("INSERT INTO usuario_equipo (usuario_id, equipo_id) VALUES (?, ?)", integrante, equipo);
            jdbc.update("INSERT INTO nomina (inscripcion_id, usuario_id, equipo_id) VALUES (?, ?, ?)", deEquipo,
                    integrante, equipo);
        }
        entityManager.clear();
        inscripcionService.pagar(capitan, deEquipo);
        reprogramacionService.reprogramar(organizador, porEquipos,
                new ReprogramacionRequest(INICIO, null, CIERRE, RECHAZO, "Lluvia"));

        assertError(() -> rechazoService.rechazar(companero, deEquipo), HttpStatus.FORBIDDEN,
                CodigoError.NO_ES_CAPITAN);

        assertThat(rechazoService.rechazar(capitan, deEquipo).reintegrado()).isEqualByComparingTo("1000.00");
        assertBilletera(capitan, "4900.00", "100.00");
    }

    @Test
    void elComprobanteSigueMostrandoElPagoConLaCustodiaReintegrada() {
        rechazoService.rechazar(jugador[0], inscripcion[0]);

        ComprobantePago comprobante = comprobanteService.deInscripcion(jugador[0], inscripcion[0]);

        assertThat(comprobante.monto()).isEqualByComparingTo("1000.00");
        assertThat(comprobante.gastosAdministrativos()).isEqualByComparingTo("100.00");
        assertThat(comprobante.custodia()).isEqualTo(EstadoCustodia.REINTEGRADA);
    }

    @Test
    void siDespuesSeCancelaElTorneoQuienRechazoRecuperaLosGastosYTodoCierra() {
        rechazoService.rechazar(jugador[0], inscripcion[0]);

        cancelacionService.cancelar(organizador, torneo, new SolicitudCancelacion("Se suspende", List.of()));

        for (Long j : jugador) {
            assertBilletera(j, "5000.00", "0.00");
        }
        assertBalanceCero();
    }

    // --- ayudas -------------------------------------------------------------------------------------

    private String detalleDelRechazo(long inscripcionId) {
        return jdbc.queryForObject("SELECT detalle::text FROM auditoria WHERE accion = 'REPROGRAMACION' "
                + "AND entidad = 'INSCRIPCION' AND entidad_id = ?", String.class, String.valueOf(inscripcionId));
    }

    private void assertBalanceCero() {
        entityManager.flush();
        List<Long> billeteras = jdbc.queryForList("SELECT id FROM billetera WHERE usuario_id IN (?, ?, ?) "
                + "OR usuario_id IS NULL", Long.class, jugador[0], jugador[1], jugador[2]);
        List<Long> custodias = jdbc.queryForList("SELECT id FROM custodia WHERE torneo_id = ?", Long.class, torneo);
        assertThat(controlDeBalance.billeteras(billeteras)).as("billeteras que no cierran").isEmpty();
        assertThat(controlDeBalance.custodias(custodias)).as("custodias que no cierran").isEmpty();
    }

    private void assertBilletera(Long usuario, String disponible, String retenido) {
        entityManager.flush();
        Map<String, Object> billetera = jdbc.queryForMap(
                "SELECT saldo_disponible, saldo_retenido FROM billetera WHERE usuario_id = ?", usuario);
        assertThat((BigDecimal) billetera.get("saldo_disponible")).isEqualByComparingTo(disponible);
        assertThat((BigDecimal) billetera.get("saldo_retenido")).isEqualByComparingTo(retenido);
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
                VALUES (?, 'Torneo bajo la lluvia', 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE',
                        'INDIVIDUAL', 4, 2, 1000, 10, now() + interval '1 day', now() + interval '7 days',
                        'INSCRIPCION_ABIERTA', now())
                RETURNING id
                """, Long.class, organizador);
        jdbc.update("UPDATE torneo SET slug = ? WHERE id = ?", "rech-" + id, id);
        return id;
    }

    private long inscripcion(long torneoId, Long usuario) {
        return jdbc.queryForObject("""
                INSERT INTO inscripcion (torneo_id, usuario_id, estado, reglamento_aceptado_en)
                VALUES (?, ?, 'PENDIENTE_PAGO', now()) RETURNING id
                """, Long.class, torneoId, usuario);
    }

    /** $5000 como una carga confirmada: sin el movimiento, el control de balance marcaría la billetera. */
    private void cargar(Long usuario) {
        jdbc.update("INSERT INTO billetera (usuario_id) VALUES (?) ON CONFLICT (usuario_id) DO NOTHING", usuario);
        jdbc.update("""
                WITH b AS (UPDATE billetera SET saldo_disponible = saldo_disponible + 5000 WHERE usuario_id = ?
                           RETURNING id, saldo_disponible)
                INSERT INTO movimiento (billetera_id, tipo, monto, saldo_resultante, estado, referencia_externa)
                SELECT b.id, 'CARGA', 5000, b.saldo_disponible, 'CONFIRMADO', 'prueba-carga-' || gen_random_uuid() FROM b
                """, usuario);
        entityManager.clear();
    }
}
