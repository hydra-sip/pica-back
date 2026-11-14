package com.hydra.pica.plataforma_pica.billetera.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.service.InscripcionService;
import com.hydra.pica.plataforma_pica.rifa.dto.PremioRifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.VentaRifaResultado;
import com.hydra.pica.plataforma_pica.rifa.service.RifaService;
import com.hydra.pica.plataforma_pica.rifa.service.VentaRifaService;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
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
 * HU-603 de punta a punta: la inscripción y la compra de números pagan con el saldo, sin cambiar su código. El torneo
 * cobra $1000 más 10 % de gastos y la rifa $500 por número más 10 %. Cada test hace rollback al terminar.
 */
@SpringBootTest(properties = "app.cobro.modo=saldo")
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class PagoConSaldoIntegracionTest {

    private static final Instant MANIANA = Instant.now().plus(1, ChronoUnit.DAYS);
    private static final Instant EN_UNA_SEMANA = Instant.now().plus(7, ChronoUnit.DAYS);

    @Autowired private InscripcionService inscripcionService;
    @Autowired private VentaRifaService ventaRifaService;
    @Autowired private RifaService rifaService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private ControlDeBalance controlDeBalance;

    /** Los usuarios del escenario: el control de balance mira sus billeteras. */
    private final List<Long> creados = new ArrayList<>();

    private Long organizador;
    private Long jugador;

    @BeforeEach
    void escenario() {
        organizador = usuario("org_saldo");
        jugador = usuario("jug_saldo");
    }

    /** RNF-CON-02: después de cada test, las billeteras y las custodias del escenario cierran contra sus movimientos. */
    @AfterEach
    void elBalanceCierra() {
        BalanceCero.assertCierra(controlDeBalance, jdbc, entityManager, creados);
    }

    // --- inscripción --------------------------------------------------------------------------------

    @Test
    void conSaldoLaInscripcionSeConfirmaYElTotalPasaAlRetenido() {
        saldo(jugador, "2000.00");
        long inscripcion = pendienteDePago(torneoConGastos());

        assertThat(inscripcionService.pagar(jugador, inscripcion).estado()).isEqualTo(EstadoInscripcion.CONFIRMADA);

        assertBilletera(jugador, "900.00", "1100.00");
        assertThat(jdbc.queryForList("SELECT tipo || ':' || monto FROM movimiento WHERE inscripcion_id = ? ORDER BY id",
                String.class, inscripcion)).containsExactly("INSCRIPCION:1000.00", "COMISION:100.00");
    }

    @Test
    void sinSaldoLaInscripcionNoOcupaCupoYElFaltanteEsDelTotal() {
        saldo(jugador, "500.00");
        long torneo = torneoConGastos();
        long inscripcion = pendienteDePago(torneo);

        SaldoInsuficienteException error = catchThrowableOfType(SaldoInsuficienteException.class,
                () -> inscripcionService.pagar(jugador, inscripcion));

        assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error.getCodigo()).isEqualTo(CodigoError.SALDO_INSUFICIENTE);
        // 1100 de cuota más gastos menos 500 de saldo, no los 500 que faltan solo para la cuota
        assertThat((BigDecimal) error.getPropiedades().get("faltante")).isEqualByComparingTo("600.00");
        assertThat(jdbc.queryForObject("SELECT estado FROM inscripcion WHERE id = ?", String.class, inscripcion))
                .isEqualTo("PENDIENTE_PAGO");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inscripcion WHERE torneo_id = ? AND estado = 'CONFIRMADA'",
                Integer.class, torneo)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento WHERE inscripcion_id = ?", Integer.class,
                inscripcion)).isZero();
        assertBilletera(jugador, "500.00", "0");
    }

    // --- compra de números --------------------------------------------------------------------------

    @Test
    void conSaldoLaCompraEmiteElTicketYElTotalPasaAlRetenido() {
        saldo(jugador, "2000.00");
        long rifa = rifaEnVenta();
        ventaRifaService.reservar(jugador, rifa, List.of(5, 6));

        VentaRifaResultado compra = ventaRifaService.comprar(jugador, rifa, List.of(5, 6), "compra-saldo-1");

        assertThat(compra.total()).isEqualByComparingTo("1100");
        assertBilletera(jugador, "900.00", "1100.00");
    }

    @Test
    void sinSaldoLaCompraNoVendeYAlCargarSePagaSinVolverAReservar() {
        saldo(jugador, "500.00");
        long rifa = rifaEnVenta();
        ventaRifaService.reservar(jugador, rifa, List.of(5, 6));

        SaldoInsuficienteException error = catchThrowableOfType(SaldoInsuficienteException.class,
                () -> ventaRifaService.comprar(jugador, rifa, List.of(5, 6), "compra-saldo-1"));

        assertThat((BigDecimal) error.getPropiedades().get("faltante")).isEqualByComparingTo("600.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket WHERE rifa_id = ?", Integer.class, rifa)).isZero();
        assertThat(jdbc.queryForList("SELECT estado FROM numero WHERE rifa_id = ? ORDER BY numero", String.class, rifa))
                .containsExactly("RESERVADO", "RESERVADO");
        assertBilletera(jugador, "500.00", "0");

        // carga lo que falta y paga con los mismos números todavía reservados
        saldo(jugador, "600.00");
        ventaRifaService.comprar(jugador, rifa, List.of(5, 6), "compra-saldo-2");

        assertThat(jdbc.queryForList("SELECT estado FROM numero WHERE rifa_id = ? ORDER BY numero", String.class, rifa))
                .containsExactly("VENDIDO", "VENDIDO");
        assertBilletera(jugador, "0", "1100.00");
    }

    // --- ayudas -------------------------------------------------------------------------------------

    /** Le carga esa plata, con su movimiento de carga para que el balance cierre. */
    private void saldo(Long usuario, String monto) {
        BalanceCero.cargar(jdbc, usuario, monto);
        entityManager.clear();
    }

    private void assertBilletera(Long usuario, String disponible, String retenido) {
        entityManager.flush();
        Map<String, Object> billetera = jdbc.queryForMap(
                "SELECT saldo_disponible, saldo_retenido FROM billetera WHERE usuario_id = ?", usuario);
        assertThat((BigDecimal) billetera.get("saldo_disponible")).isEqualByComparingTo(disponible);
        assertThat((BigDecimal) billetera.get("saldo_retenido")).isEqualByComparingTo(retenido);
    }

    private long torneoConGastos() {
        return jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, valor_inscripcion,
                                    pct_gastos_administrativos, cierre_inscripcion_en, fecha_inicio, estado)
                VALUES (?, 'Torneo con saldo', 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE', 'INDIVIDUAL',
                        8, 2, 1000, 10, now() + interval '1 day', now() + interval '7 days', 'INSCRIPCION_ABIERTA')
                RETURNING id
                """, Long.class, organizador);
    }

    private long pendienteDePago(long torneo) {
        return jdbc.queryForObject("""
                INSERT INTO inscripcion (torneo_id, usuario_id, estado, reglamento_aceptado_en)
                VALUES (?, ?, 'PENDIENTE_PAGO', now()) RETURNING id
                """, Long.class, torneo, jugador);
    }

    /** Del 1 al 100 a $500, con la venta abierta desde hace una hora. */
    private long rifaEnVenta() {
        RifaDetalle creada = rifaService.crear(organizador, new RifaRequest("Rifa con saldo", null, 1, 100,
                new BigDecimal("500"), MANIANA, EN_UNA_SEMANA, EN_UNA_SEMANA.plus(1, ChronoUnit.DAYS), null, null));
        rifaService.reemplazarPremios(organizador, creada.id(), List.of(new PremioRifaRequest(new BigDecimal("100"), null)));
        rifaService.publicar(organizador, creada.id());
        jdbc.update("UPDATE rifa SET venta_desde = now() - interval '1 hour' WHERE id = ?", creada.id());
        entityManager.clear();
        return creada.id();
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
