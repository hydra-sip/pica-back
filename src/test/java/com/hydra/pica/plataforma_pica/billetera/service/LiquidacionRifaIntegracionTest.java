package com.hydra.pica.plataforma_pica.billetera.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.billetera.service.LiquidacionRifaService.LiquidacionRifa;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.inscripcion.service.InscripcionService;
import com.hydra.pica.plataforma_pica.rifa.dto.PremioRifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaRequest;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.rifa.service.RifaService;
import com.hydra.pica.plataforma_pica.rifa.service.TicketService;
import com.hydra.pica.plataforma_pica.rifa.service.VentaRifaService;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
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
 * La liquidación de la rifa (HU-606, PICA-330). La rifa vende a $500 más 10 % y premia 60 % al 1.º y 40 % al 2.º;
 * Ana compra el 1 y el 2 y Beto el 3 ($1500 vendidos, $150 de gastos). En el sorteo el 3 sale 1.º y el 1 sale 2.º. El
 * sorteo y el plazo se ponen a mano: sorteada hace 50 h, con el plazo de 48 h vencido. Cada test hace rollback.
 */
@SpringBootTest(properties = "app.cobro.modo=saldo")
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class LiquidacionRifaIntegracionTest {

    private static final Instant MANIANA = Instant.now().plus(1, ChronoUnit.DAYS);
    private static final Instant EN_UNA_SEMANA = Instant.now().plus(7, ChronoUnit.DAYS);

    @Autowired private LiquidacionRifaService liquidacionRifaService;
    @Autowired private LiquidacionService liquidacionService;
    @Autowired private RifaService rifaService;
    @Autowired private VentaRifaService ventaRifaService;
    @Autowired private TicketService ticketService;
    @Autowired private InscripcionService inscripcionService;
    @Autowired private RifaRepository rifaRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private Long organizador;
    private Long ana;
    private Long beto;
    private BigDecimal plataformaAntes;
    private long rifa;

    @BeforeEach
    void escenario() {
        organizador = usuario("org_liq_rifa");
        ana = usuario("ana_liq_rifa");
        beto = usuario("beto_liq_rifa");
        saldo(ana, "10000.00");
        saldo(beto, "10000.00");
        plataformaAntes = deLaPlataforma();
        rifa = rifaEnVenta(List.of(60, 40));
        comprar(ana, 1, 2);
        comprar(beto, 3);
    }

    // --- el reparto ---------------------------------------------------------------------------------

    @Test
    void cadaGanadorCobraSuPremioYLaRifaQuedaLiquidada() {
        sortear(3, 1);

        LiquidacionRifa liquidacion = liquidacionRifaService.liquidarRifa(rifa);

        assertThat(liquidacion.retenido()).isEqualByComparingTo("1650.00");
        assertThat(liquidacion.aportePendiente()).isFalse();
        assertBilletera(beto, "10350.00", "0.00");   // 10000 − 550 + 60 % de 1500
        assertBilletera(ana, "9500.00", "0.00");     // 10000 − 1100 + 40 % de 1500
        assertThat(deLaPlataforma().subtract(plataformaAntes)).isEqualByComparingTo("150.00");
        assertThat(rifaRepository.findById(rifa).orElseThrow().getEstado().name()).isEqualTo("LIQUIDADA");
        assertThat(jdbc.queryForObject("SELECT estado FROM custodia WHERE rifa_id = ?", String.class, rifa))
                .isEqualTo("LIBERADA");
    }

    @Test
    void cadaAcreditacionLlevaLaRifaYQuedaEnLaAuditoria() {
        sortear(3, 1);

        liquidacionRifaService.liquidarRifa(rifa);

        assertThat(jdbc.queryForList("""
                SELECT tipo || ':' || monto FROM movimiento WHERE rifa_id = ? ORDER BY referencia_externa
                """, String.class, rifa))
                .containsExactly("LIQUIDACION:150.00", "PREMIO:900.00", "PREMIO:600.00");
        assertThat(jdbc.queryForObject("SELECT descripcion FROM movimiento WHERE referencia_externa = ?",
                String.class, "liq-r" + rifa + "-premio-1")).isEqualTo("Premio del 1.º puesto en la rifa Rifa a liquidar");
        assertThat(jdbc.queryForList("""
                SELECT detalle->>'tipo' FROM auditoria
                WHERE accion = 'LIQUIDACION' AND entidad = 'RIFA' AND entidad_id = ?
                """, String.class, String.valueOf(rifa)))
                .containsExactlyInAnyOrder("LIQUIDACION_PREMIO", "LIQUIDACION_PREMIO", "LIQUIDACION_RIFA");
    }

    @Test
    void elTicketGanadorMuestraElPremioAcreditado() {
        sortear(3, 1);
        assertThat(ticketService.detalle(beto, ticketDe(beto)).premioAcreditado()).isNull();

        liquidacionRifaService.liquidarRifa(rifa);
        entityManager.flush();
        entityManager.clear();

        assertThat(ticketService.detalle(beto, ticketDe(beto)).premioAcreditado()).isEqualByComparingTo("900.00");
        assertThat(ticketService.detalle(ana, ticketDe(ana)).premioAcreditado()).isEqualByComparingTo("600.00");
    }

    @Test
    void unPremioSinGanadorSeReparteEntreLosQueTienen() {
        sortear(3, null);

        liquidacionRifaService.liquidarRifa(rifa);

        assertBilletera(beto, "10950.00", "0.00");   // 10000 − 550 + los 1500
        assertBilletera(ana, "8900.00", "0.00");
    }

    // --- el aporte al pozo del torneo ---------------------------------------------------------------

    @Test
    void conElTorneoSinTerminarElAporteEsperaYLoPagaLaLiquidacionDelTorneo() {
        long torneo = torneoConDosJugadores();
        conTorneo(torneo);
        sortear(3, 1);

        LiquidacionRifa liquidacion = liquidacionRifaService.liquidarRifa(rifa);

        // 20 % de 1500 va al torneo y los premios se reparten los 1200 restantes: 720 y 480
        assertThat(liquidacion.aporteAlTorneo()).isEqualByComparingTo("300.00");
        assertThat(liquidacion.aportePendiente()).isTrue();
        // cobró su premio; lo que pagó sigue retenido mientras la custodia de la rifa guarda el aporte
        assertBilletera(beto, "10170.00", "550.00");   // 9450 + 60 % de 1200
        assertThat(jdbc.queryForObject("SELECT estado FROM custodia WHERE rifa_id = ?", String.class, rifa))
                .isEqualTo("RETENIDA");

        terminar(torneo);
        liquidacionService.liquidarTorneo(torneo);

        // el 1.º del torneo cobra el pozo (2000) y lo que aportó la rifa (300)
        assertBilletera(jugador1, "11200.00", "0.00");   // 10000 − 1100 + 2000 + 300
        assertBilletera(beto, "10170.00", "0.00");
        assertThat(jdbc.queryForObject("SELECT estado FROM custodia WHERE rifa_id = ?", String.class, rifa))
                .isEqualTo("LIBERADA");
        assertThat(jdbc.queryForMap("""
                SELECT tipo, torneo_id, rifa_id FROM movimiento WHERE referencia_externa LIKE ?
                """, "liq-r" + rifa + "-aporte-%"))
                .containsEntry("tipo", "PREMIO").containsEntry("torneo_id", torneo).containsEntry("rifa_id", rifa);
    }

    @Test
    void conElTorneoYaLiquidadoElAporteSePagaAlLiquidarLaRifa() {
        long torneo = torneoConDosJugadores();
        conTorneo(torneo);
        terminar(torneo);
        liquidacionService.liquidarTorneo(torneo);
        sortear(3, 1);

        LiquidacionRifa liquidacion = liquidacionRifaService.liquidarRifa(rifa);

        assertThat(liquidacion.aportePendiente()).isFalse();
        assertBilletera(jugador1, "11200.00", "0.00");
        assertThat(jdbc.queryForObject("SELECT estado FROM custodia WHERE rifa_id = ?", String.class, rifa))
                .isEqualTo("LIBERADA");
        // lo repartido entre la rifa y el torneo da lo que retenía la rifa
        assertThat(jdbc.queryForObject("SELECT sum(monto) FROM movimiento WHERE referencia_externa LIKE ?",
                BigDecimal.class, "liq-r" + rifa + "-%")).isEqualByComparingTo("1650.00");
    }

    // --- una sola vez y solo terminada --------------------------------------------------------------

    @Test
    void unReintentoNoPagaDosVeces() {
        sortear(3, 1);
        liquidacionRifaService.liquidarRifa(rifa);
        entityManager.flush();

        assertError(() -> liquidacionRifaService.liquidarRifa(rifa), CodigoError.CUSTODIA_LIBERADA);
        assertBilletera(beto, "10350.00", "0.00");
    }

    @Test
    void conElPlazoDeReclamoAbiertoNoSeLiquida() {
        sortear(3, 1);
        jdbc.update("UPDATE rifa SET sorteada_en = now() - interval '1 hour' WHERE id = ?", rifa);
        entityManager.clear();

        assertError(() -> liquidacionRifaService.liquidarRifa(rifa), CodigoError.FONDOS_EN_CUSTODIA);
        assertThat(paraLiquidar()).doesNotContain(rifa);
    }

    @Test
    void enRevisionNoSeLiquida() {
        sortear(3, 1);
        jdbc.update("UPDATE rifa SET estado = 'EN_REVISION' WHERE id = ?", rifa);
        entityManager.clear();

        assertError(() -> liquidacionRifaService.liquidarRifa(rifa), CodigoError.FONDOS_EN_CUSTODIA);
        assertThat(paraLiquidar()).doesNotContain(rifa);
        assertBilletera(beto, "9450.00", "550.00");
    }

    @Test
    void conUnReclamoPendienteNoSeLiquida() {
        sortear(3, 1);
        jdbc.update("INSERT INTO reclamo_rifa (rifa_id, usuario_id, motivo, estado) VALUES (?, ?, 'No me cierra', 'PENDIENTE')",
                rifa, ana);

        assertError(() -> liquidacionRifaService.liquidarRifa(rifa), CodigoError.FONDOS_EN_CUSTODIA);
        assertThat(paraLiquidar()).doesNotContain(rifa);
    }

    @Test
    void enVentaNoSeLiquida() {
        assertThatThrownBy(() -> liquidacionRifaService.liquidarRifa(rifa))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCodigo()).isEqualTo(CodigoError.FONDOS_EN_CUSTODIA);
                    assertThat(e.getMessage()).isEqualTo(
                            "La rifa debe estar sorteada y con el plazo de reclamo vencido para liberar la custodia");
                });
    }

    @Test
    void laRevisionEncuentraLasSorteadasConElPlazoVencido() {
        sortear(3, 1);

        assertThat(paraLiquidar()).contains(rifa);
    }

    // --- ayudas -------------------------------------------------------------------------------------

    private Long jugador1;

    /** Un torneo de $1000 más 10 % con dos jugadores que pagaron: el pozo es 2000 y va entero al 1.º. */
    private long torneoConDosJugadores() {
        long torneo = jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, valor_inscripcion,
                                    pct_gastos_administrativos, cierre_inscripcion_en, fecha_inicio, estado)
                VALUES (?, 'Torneo con rifa', 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE', 'INDIVIDUAL',
                        8, 2, 1000, 10, now() + interval '1 day', now() + interval '7 days', 'INSCRIPCION_ABIERTA')
                RETURNING id
                """, Long.class, organizador);
        jdbc.update("INSERT INTO premio (torneo_id, puesto, pct) VALUES (?, 1, 100)", torneo);
        jugador1 = usuario("j1_liq_rifa");
        Long jugador2 = usuario("j2_liq_rifa");
        for (Long jugador : List.of(jugador1, jugador2)) {
            saldo(jugador, "10000.00");
            long inscripcion = jdbc.queryForObject("""
                    INSERT INTO inscripcion (torneo_id, usuario_id, estado, reglamento_aceptado_en)
                    VALUES (?, ?, 'PENDIENTE_PAGO', now()) RETURNING id
                    """, Long.class, torneo, jugador);
            inscripcionService.pagar(jugador, inscripcion);
        }
        entityManager.flush();
        return torneo;
    }

    private void terminar(long torneo) {
        jdbc.update("""
                UPDATE inscripcion SET posicion = CASE WHEN usuario_id = ? THEN 1 ELSE 2 END WHERE torneo_id = ?
                """, jugador1, torneo);
        jdbc.update("UPDATE torneo SET estado = 'FINALIZADO' WHERE id = ?", torneo);
        entityManager.clear();
    }

    /** La rifa pasa a aportar 20 % al torneo: los premios quedan en 80 % (48 y 32). */
    private void conTorneo(long torneo) {
        jdbc.update("UPDATE rifa SET torneo_id = ?, pct_destinado_torneo = 20 WHERE id = ?", torneo, rifa);
        jdbc.update("UPDATE premio_rifa SET pct = CASE puesto WHEN 1 THEN 48 ELSE 32 END WHERE rifa_id = ?", rifa);
        entityManager.clear();
    }

    /** El resultado del sorteo, hace 50 h: el número de cada puesto (null si no hubo ganador). */
    private void sortear(Integer primero, Integer segundo) {
        jdbc.update("UPDATE premio_rifa SET numero_ganador = ? WHERE rifa_id = ? AND puesto = 1", primero, rifa);
        jdbc.update("UPDATE premio_rifa SET numero_ganador = ? WHERE rifa_id = ? AND puesto = 2", segundo, rifa);
        jdbc.update("""
                UPDATE rifa SET estado = 'SORTEADA', venta_desde = now() - interval '10 days',
                                venta_hasta = now() - interval '5 days', sorteada_en = now() - interval '50 hours'
                WHERE id = ?
                """, rifa);
        entityManager.clear();
    }

    private List<Long> paraLiquidar() {
        return rifaRepository.paraLiquidar(Instant.now().minus(48, ChronoUnit.HOURS));
    }

    private long rifaEnVenta(List<Integer> pcts) {
        RifaDetalle creada = rifaService.crear(organizador, new RifaRequest("Rifa a liquidar", null, 1, 100,
                new BigDecimal("500"), MANIANA, EN_UNA_SEMANA, EN_UNA_SEMANA.plus(1, ChronoUnit.DAYS), null, null));
        rifaService.reemplazarPremios(organizador, creada.id(), pcts.stream()
                .map(pct -> new PremioRifaRequest(BigDecimal.valueOf(pct), null)).toList());
        rifaService.publicar(organizador, creada.id());
        jdbc.update("UPDATE rifa SET venta_desde = now() - interval '1 hour' WHERE id = ?", creada.id());
        entityManager.clear();
        return creada.id();
    }

    private void comprar(Long comprador, Integer... numeros) {
        ventaRifaService.reservar(comprador, rifa, List.of(numeros));
        ventaRifaService.comprar(comprador, rifa, List.of(numeros), "liq-rifa-" + comprador);
        entityManager.flush();
    }

    private long ticketDe(Long comprador) {
        return jdbc.queryForObject("SELECT id FROM ticket WHERE comprador_id = ? AND rifa_id = ?", Long.class,
                comprador, rifa);
    }

    private static void assertError(ThrowingCallable accion, CodigoError codigo) {
        assertThatThrownBy(accion).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getCodigo()).isEqualTo(codigo);
        });
    }

    private void saldo(Long usuario, String disponible) {
        jdbc.update("""
                INSERT INTO billetera (usuario_id, saldo_disponible) VALUES (?, ?)
                ON CONFLICT (usuario_id) DO UPDATE SET saldo_disponible = excluded.saldo_disponible
                """, usuario, new BigDecimal(disponible));
        entityManager.clear();
    }

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
        return usuarioRepository.save(usuario).getId();
    }
}
