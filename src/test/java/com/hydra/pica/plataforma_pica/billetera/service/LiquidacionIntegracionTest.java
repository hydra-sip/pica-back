package com.hydra.pica.plataforma_pica.billetera.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.billetera.dto.RepartoLiquidacion;
import com.hydra.pica.plataforma_pica.billetera.repository.CustodiaRepository;
import com.hydra.pica.plataforma_pica.billetera.service.LiquidacionService.LiquidacionTorneo;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.inscripcion.service.InscripcionService;
import com.hydra.pica.plataforma_pica.torneo.service.TorneoPublicoService;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
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
 * La liquidación de un torneo finalizado (HU-606, PICA-329). Cuatro jugadores pagan con saldo $1000 más 10 % (hay
 * $4400 en custodia), el torneo tiene $500 de gastos y premia 70 % al 1.º y 30 % al 2.º: el pozo es $3500. Los
 * puestos se ponen a mano, como los deja la final. Cada test hace rollback al terminar.
 */
@SpringBootTest(properties = "app.cobro.modo=saldo")
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class LiquidacionIntegracionTest {

    @Autowired private LiquidacionService liquidacionService;
    @Autowired private RepartoLiquidacionService repartoLiquidacionService;
    @Autowired private CustodiaRepository custodiaRepository;
    @Autowired private InscripcionService inscripcionService;
    @Autowired private TorneoPublicoService torneoPublicoService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private ControlDeBalance controlDeBalance;

    /** Los usuarios del escenario: el control de balance mira sus billeteras. */
    private final List<Long> creados = new ArrayList<>();
    private boolean diferenciaAProposito;

    private Long organizador;
    private BigDecimal plataformaAntes;
    private long torneo;
    private final Long[] jugador = new Long[4];
    private final long[] inscripcion = new long[4];

    @BeforeEach
    void escenario() {
        organizador = usuario("org_liq");
        plataformaAntes = deLaPlataforma();
        torneo = torneo("INDIVIDUAL");
        jdbc.update("INSERT INTO premio (torneo_id, puesto, pct) VALUES (?, 1, 70), (?, 2, 30)", torneo, torneo);
        jdbc.update("INSERT INTO gasto (torneo_id, gasto_id, descripcion, monto) VALUES (?, 1, 'Cancha', 500)", torneo);
        for (int i = 0; i < 4; i++) {
            jugador[i] = usuario("jug_liq_" + i);
            saldo(jugador[i], "5000.00");
            inscripcion[i] = jdbc.queryForObject("""
                    INSERT INTO inscripcion (torneo_id, usuario_id, estado, reglamento_aceptado_en)
                    VALUES (?, ?, 'PENDIENTE_PAGO', now()) RETURNING id
                    """, Long.class, torneo, jugador[i]);
            inscripcionService.pagar(jugador[i], inscripcion[i]);
        }
        entityManager.flush();
    }

    /** RNF-CON-02: después de cada test, las billeteras y las custodias del escenario cierran contra sus movimientos. */
    @AfterEach
    void elBalanceCierra() {
        if (!diferenciaAProposito) {
            BalanceCero.assertCierra(controlDeBalance, jdbc, entityManager, creados);
        }
    }

    // --- el reparto ---------------------------------------------------------------------------------

    @Test
    void alFinalizarCadaUnoRecibeLoQueDiceElEsquema() {
        terminar(1, 2, 3, 3);

        LiquidacionTorneo liquidacion = liquidacionService.liquidarTorneo(torneo);

        assertThat(liquidacion.retenido()).isEqualByComparingTo("4400.00");
        assertBilletera(jugador[0], "6350.00", "0.00");   // 3900 + 70 % de 3500
        assertBilletera(jugador[1], "4950.00", "0.00");   // 3900 + 30 % de 3500
        assertBilletera(jugador[2], "3900.00", "0.00");
        assertBilletera(jugador[3], "3900.00", "0.00");
        assertBilletera(organizador, "500.00", "0.00");   // los gastos del torneo
        assertThat(deLaPlataforma().subtract(plataformaAntes)).isEqualByComparingTo("400.00");
        assertThat(jdbc.queryForObject("SELECT ganancia_acumulada FROM billetera WHERE usuario_id = ?",
                BigDecimal.class, jugador[0])).isEqualByComparingTo("2450.00");
    }

    @Test
    void loRepartidoEsIgualALoRetenidoYLaCustodiaQuedaLiberada() {
        terminar(1, 2, 3, 3);

        liquidacionService.liquidarTorneo(torneo);

        assertThat(jdbc.queryForObject("SELECT sum(monto) FROM movimiento WHERE referencia_externa LIKE ?",
                BigDecimal.class, "liq-t" + torneo + "-%")).isEqualByComparingTo("4400.00");
        assertThat(jdbc.queryForObject("SELECT estado FROM custodia WHERE torneo_id = ?", String.class, torneo))
                .isEqualTo("LIBERADA");
        assertThat(custodiaRepository.torneosFinalizadosSinLiquidar()).doesNotContain(torneo);
    }

    @Test
    void elPremioEsUnMovimientoDelTorneoConSuInscripcionYCadaAcreditacionQuedaEnLaAuditoria() {
        terminar(1, 2, 3, 3);

        liquidacionService.liquidarTorneo(torneo);

        assertThat(jdbc.queryForMap("""
                SELECT m.tipo, m.monto, m.torneo_id, m.inscripcion_id, m.descripcion, m.estado
                FROM movimiento m JOIN billetera b ON b.id = m.billetera_id
                WHERE b.usuario_id = ? AND m.tipo = 'PREMIO'
                """, jugador[0])).containsEntry("monto", new BigDecimal("2450.00"))
                .containsEntry("torneo_id", torneo)
                .containsEntry("inscripcion_id", inscripcion[0])
                .containsEntry("descripcion", "Premio del 1.º puesto en Torneo a liquidar")
                .containsEntry("estado", "CONFIRMADO");
        assertThat(jdbc.queryForList("""
                SELECT m.tipo FROM movimiento m JOIN billetera b ON b.id = m.billetera_id
                WHERE m.referencia_externa LIKE ? AND (b.usuario_id = ? OR b.usuario_id IS NULL)
                """, String.class, "liq-t" + torneo + "-%", organizador))
                .containsOnly("LIQUIDACION").hasSize(2);
        assertThat(jdbc.queryForList("""
                SELECT detalle->>'tipo' FROM auditoria
                WHERE accion = 'LIQUIDACION' AND entidad = 'TORNEO' AND entidad_id = ? ORDER BY id
                """, String.class, String.valueOf(torneo)))
                .containsExactlyInAnyOrder("LIQUIDACION_PREMIO", "LIQUIDACION_PREMIO", "LIQUIDACION_TORNEO",
                        "LIQUIDACION_TORNEO");
    }

    @Test
    void elOrganizadorVeElRepartoQueHizoLaLiquidacion() {
        assertThat(repartoLiquidacionService.delTorneo(organizador, torneo).liquidado()).isFalse();
        assertThat(repartoLiquidacionService.delTorneo(organizador, torneo).lineas()).isEmpty();
        terminar(1, 2, 3, 3);

        liquidacionService.liquidarTorneo(torneo);
        entityManager.flush();
        entityManager.clear();

        RepartoLiquidacion reparto = repartoLiquidacionService.delTorneo(organizador, torneo);
        assertThat(reparto.liquidado()).isTrue();
        assertThat(reparto.liquidadoEn()).isNotNull();
        assertThat(reparto.total()).isEqualByComparingTo("4400.00");
        assertThat(reparto.lineas()).extracting(l -> l.rol() + ":" + l.puesto() + ":" + l.beneficiarioUsername()
                        + ":" + l.monto().toPlainString())
                .containsExactlyInAnyOrder("PREMIO:1:jug_liq_0:2450.00", "PREMIO:2:jug_liq_1:1050.00",
                        "ORGANIZADOR:null:org_liq:500.00", "PLATAFORMA:null:null:400.00");
        assertThatThrownBy(() -> repartoLiquidacionService.delTorneo(jugador[0], torneo))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCodigo()).isEqualTo(CodigoError.TORNEO_NO_ENCONTRADO));
    }

    @Test
    void losQuePierdenEnSemifinalesSeRepartenEl3YEl4() {
        jdbc.update("DELETE FROM premio WHERE torneo_id = ?", torneo);
        jdbc.update("INSERT INTO premio (torneo_id, puesto, pct) VALUES (?, 1, 50), (?, 2, 20), (?, 3, 20), (?, 4, 10)",
                torneo, torneo, torneo, torneo);
        terminar(1, 2, 3, 3);

        liquidacionService.liquidarTorneo(torneo);

        assertBilletera(jugador[0], "5650.00", "0.00");   // 3900 + 50 % de 3500
        assertBilletera(jugador[1], "4600.00", "0.00");   // 3900 + 20 %
        assertBilletera(jugador[2], "4425.00", "0.00");   // 3900 + (20 + 10) / 2 %
        assertBilletera(jugador[3], "4425.00", "0.00");
    }

    @Test
    void siLoRecaudadoNoCubreLosGastosTodoVaAlOrganizador() {
        jdbc.update("UPDATE gasto SET monto = 10000 WHERE torneo_id = ?", torneo);
        terminar(1, 2, 3, 3);

        liquidacionService.liquidarTorneo(torneo);

        assertBilletera(organizador, "4000.00", "0.00");
        assertBilletera(jugador[0], "3900.00", "0.00");
        assertThat(deLaPlataforma().subtract(plataformaAntes)).isEqualByComparingTo("400.00");
    }

    @Test
    void enUnTorneoPorEquiposElPremioLoCobraElCapitan() {
        long porEquipos = torneo("EQUIPO");
        jdbc.update("INSERT INTO premio (torneo_id, puesto, pct) VALUES (?, 1, 100)", porEquipos);
        long ganadora = inscripcionDeEquipo(porEquipos, jugador[2], "Los de arriba");
        long perdedora = inscripcionDeEquipo(porEquipos, jugador[3], "Los de abajo");
        pagado(porEquipos, ganadora, jugador[2]);
        pagado(porEquipos, perdedora, jugador[3]);
        jdbc.update("UPDATE inscripcion SET posicion = 1 WHERE id = ?", ganadora);
        jdbc.update("UPDATE inscripcion SET posicion = 2 WHERE id = ?", perdedora);
        jdbc.update("UPDATE torneo SET estado = 'FINALIZADO' WHERE id = ?", porEquipos);
        entityManager.clear();

        liquidacionService.liquidarTorneo(porEquipos);

        // cada capitán pagó 1100 del torneo de arriba y 1100 de este: le quedan 2800 y la custodia vuelve 0
        assertBilletera(jugador[2], "4800.00", "1100.00");   // 2800 + el pozo de 2000
        assertBilletera(jugador[3], "2800.00", "1100.00");
    }

    @Test
    void unTorneoSinPagosQuedaLiquidadoYLaFichaLoMuestra() {
        long gratis = torneo("INDIVIDUAL");
        jdbc.update("UPDATE torneo SET estado = 'FINALIZADO' WHERE id = ?", gratis);
        entityManager.clear();

        LiquidacionTorneo liquidacion = liquidacionService.liquidarTorneo(gratis);

        assertThat(liquidacion.acreditaciones()).isEmpty();
        assertThat(torneoPublicoService.detallePublico("liq-" + gratis).liquidadoEn()).isNotNull();
    }

    @Test
    void laFichaNoMuestraLiquidadoHastaQueSeLiquida() {
        assertThat(torneoPublicoService.detallePublico("liq-" + torneo).liquidadoEn()).isNull();
    }

    // --- una sola vez y solo terminado --------------------------------------------------------------

    @Test
    void unReintentoNoPagaDosVeces() {
        terminar(1, 2, 3, 3);
        liquidacionService.liquidarTorneo(torneo);
        entityManager.flush();

        assertError(() -> liquidacionService.liquidarTorneo(torneo), HttpStatus.CONFLICT, CodigoError.CUSTODIA_LIBERADA);
        assertBilletera(jugador[0], "6350.00", "0.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento WHERE referencia_externa LIKE ?",
                Integer.class, "liq-t" + torneo + "-%")).isEqualTo(4);
    }

    @Test
    void unTorneoEnCursoNoSeLiquida() {
        jdbc.update("UPDATE torneo SET estado = 'EN_CURSO' WHERE id = ?", torneo);
        entityManager.clear();

        assertThatThrownBy(() -> liquidacionService.liquidarTorneo(torneo))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCodigo()).isEqualTo(CodigoError.FONDOS_EN_CUSTODIA);
                    assertThat(e.getMessage())
                            .isEqualTo("El evento debe estar cerrado y validado oficialmente para liberar la custodia");
                });
        assertBilletera(jugador[0], "3900.00", "1100.00");
        assertThat(custodiaRepository.torneosFinalizadosSinLiquidar()).doesNotContain(torneo);
    }

    @Test
    void unTorneoCanceladoTampocoSeLiquida() {
        jdbc.update("UPDATE torneo SET estado = 'CANCELADO', motivo_cancelacion = 'Lluvia' WHERE id = ?", torneo);
        entityManager.clear();

        assertError(() -> liquidacionService.liquidarTorneo(torneo), HttpStatus.CONFLICT,
                CodigoError.FONDOS_EN_CUSTODIA);
    }

    @Test
    void siLoRepartidoNoDaLoRetenidoNoSePagaNadaYQuedaUnaAlerta() {
        terminar(1, 2, 3, 3);
        // algo que la liquidación no sabe repartir: es la diferencia que tiene que encontrar el control
        diferenciaAProposito = true;
        jdbc.update("""
                INSERT INTO movimiento (billetera_id, torneo_id, tipo, monto, saldo_resultante, estado, custodia_id,
                                        referencia_externa)
                SELECT b.id, ?, 'REINTEGRO', 10, 0, 'CONFIRMADO', c.id, ?
                FROM billetera b, custodia c WHERE b.usuario_id = ? AND c.torneo_id = ?
                """, torneo, "rara-" + torneo, jugador[0], torneo);

        assertThatThrownBy(() -> liquidacionService.liquidarTorneo(torneo)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento WHERE referencia_externa LIKE ?",
                Integer.class, "liq-t" + torneo + "-%")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auditoria
                WHERE accion = 'LIQUIDACION' AND entidad_id = ? AND detalle->>'alerta' = 'REPARTO_NO_CUADRA'
                """, Integer.class, String.valueOf(torneo))).isEqualTo(1);
    }

    @Test
    void laRevisionEncuentraLosFinalizadosSinLiquidar() {
        terminar(1, 2, 3, 3);

        assertThat(custodiaRepository.torneosFinalizadosSinLiquidar()).contains(torneo);
    }

    // --- ayudas -------------------------------------------------------------------------------------

    /** Los puestos de cada jugador, como los deja la final, y el torneo FINALIZADO. */
    private void terminar(int... posiciones) {
        for (int i = 0; i < posiciones.length; i++) {
            jdbc.update("UPDATE inscripcion SET posicion = ? WHERE id = ?", posiciones[i], inscripcion[i]);
        }
        jdbc.update("UPDATE torneo SET estado = 'FINALIZADO' WHERE id = ?", torneo);
        entityManager.clear();
    }

    private long torneo(String tipo) {
        boolean equipos = tipo.equals("EQUIPO");
        long id = jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, tamanio_equipo, nomina_maxima, cupo_maximo, cupo_minimo,
                                    valor_inscripcion, pct_gastos_administrativos, cierre_inscripcion_en, fecha_inicio,
                                    estado, publicado_en)
                VALUES (?, 'Torneo a liquidar', 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE', ?, ?, ?,
                        8, 2, 1000, 10, now() + interval '1 day', now() + interval '7 days', 'INSCRIPCION_ABIERTA',
                        now())
                RETURNING id
                """, Long.class, organizador, tipo, equipos ? 1 : null, equipos ? 2 : null);
        jdbc.update("UPDATE torneo SET slug = ? WHERE id = ?", "liq-" + id, id);
        return id;
    }

    private long inscripcionDeEquipo(long torneoId, Long capitan, String nombre) {
        long equipo = jdbc.queryForObject("INSERT INTO equipo (capitan_id, nombre) VALUES (?, ?) RETURNING id",
                Long.class, capitan, nombre);
        return jdbc.queryForObject("""
                INSERT INTO inscripcion (torneo_id, equipo_id, estado, reglamento_aceptado_en)
                VALUES (?, ?, 'CONFIRMADA', now()) RETURNING id
                """, Long.class, torneoId, equipo);
    }

    /** Lo que deja el cobro con saldo de una inscripción: $1000 más $100 en custodia, retenidos al capitán. */
    private void pagado(long torneoId, long inscripcionId, Long capitan) {
        jdbc.update("INSERT INTO custodia (torneo_id) VALUES (?) ON CONFLICT (torneo_id) DO NOTHING", torneoId);
        jdbc.update("""
                UPDATE billetera SET saldo_disponible = saldo_disponible - 1100, saldo_retenido = saldo_retenido + 1100
                WHERE usuario_id = ?
                """, capitan);
        jdbc.update("""
                INSERT INTO movimiento (billetera_id, torneo_id, inscripcion_id, tipo, monto, saldo_resultante, estado,
                                        custodia_id, referencia_externa)
                SELECT b.id, ?, ?, t.tipo, t.monto, b.saldo_disponible, 'CONFIRMADO', c.id, 'pago-eq-' || t.tipo || ?
                FROM billetera b, custodia c, (VALUES ('INSCRIPCION', 1000), ('COMISION', 100)) AS t (tipo, monto)
                WHERE b.usuario_id = ? AND c.torneo_id = ?
                """, torneoId, inscripcionId, inscripcionId, capitan, torneoId);
    }

    private static void assertError(ThrowingCallable accion, HttpStatus status, CodigoError codigo) {
        assertThatThrownBy(accion).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCodigo()).isEqualTo(codigo);
        });
    }

    /** Le carga esa plata, con su movimiento de carga para que el balance cierre. */
    private void saldo(Long usuario, String monto) {
        BalanceCero.cargar(jdbc, usuario, monto);
        entityManager.clear();
    }

    /** La billetera de la plataforma: la única sin usuario (V40). */
    private BigDecimal deLaPlataforma() {
        entityManager.flush();
        return jdbc.queryForObject("SELECT saldo_disponible FROM billetera WHERE usuario_id IS NULL", BigDecimal.class);
    }

    private void assertBilletera(Long usuario, String disponible, String retenido) {
        entityManager.flush();
        Map<String, Object> billetera = jdbc.queryForMap(
                "SELECT saldo_disponible, saldo_retenido FROM billetera WHERE usuario_id = ?", usuario);
        assertThat((BigDecimal) billetera.get("saldo_disponible")).isEqualByComparingTo(disponible);
        assertThat((BigDecimal) billetera.get("saldo_retenido")).isEqualByComparingTo(retenido);
    }

    private Long usuario(String nombre) {
        Persona persona = new Persona();
        persona.setNombres(nombre);
        persona.setApellidos("Test");
        persona.setEstado(EstadoGeneral.ACTIVO);
        persona = personaRepository.save(persona);

        Usuario usuario = new Usuario();
        usuario.setUsername(nombre);
        usuario.setEmail(nombre + "@example.com");
        usuario.setEstado(EstadoUsuario.ACTIVO);
        usuario.setEmailVerificado(true);
        usuario.setPersona(persona);
        Long id = usuarioRepository.save(usuario).getId();
        creados.add(id);
        return id;
    }
}
