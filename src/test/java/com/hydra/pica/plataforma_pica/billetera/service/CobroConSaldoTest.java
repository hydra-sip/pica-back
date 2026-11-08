package com.hydra.pica.plataforma_pica.billetera.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.EntityManager;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.billetera.domain.Billetera;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoBilletera;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.repository.BilleteraRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.MovimientoRepository;
import com.hydra.pica.plataforma_pica.common.config.JpaAuditingConfig;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.security.SecurityContextCurrentUserProvider;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * HU-603: el cobro con el saldo de la billetera, contra Postgres. Cada test hace rollback; la concurrencia está en
 * {@link CobroConSaldoConcurrenteIntegracionTest}.
 */
@DataJpaTest(properties = "app.cobro.modo=saldo")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        CobroConSaldo.class,
        JpaAuditingConfig.class,
        SecurityContextCurrentUserProvider.class,
        TestcontainersConfiguration.class
})
class CobroConSaldoTest {

    private static final BigDecimal MIL = new BigDecimal("1000.00");

    @Autowired private CobroService cobroService;
    @Autowired private EntityManager entityManager;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private TorneoRepository torneoRepository;
    @Autowired private InscripcionRepository inscripcionRepository;
    @Autowired private BilleteraRepository billeteraRepository;
    @Autowired private MovimientoRepository movimientoRepository;

    private Usuario jugador;
    private Torneo torneo;
    private Inscripcion inscripcion;

    @BeforeEach
    void datos() {
        Usuario organizador = usuario("organizador");
        jugador = usuario("jugador");
        torneo = torneoRepository.save(torneo(organizador));
        inscripcion = new Inscripcion();
        inscripcion.setTorneo(torneo);
        inscripcion.setUsuario(jugador);
        inscripcion.setReglamentoAceptadoEn(Instant.now());
        inscripcion = inscripcionRepository.save(inscripcion);
    }

    @Test
    void elCobroEsElConSaldo() {
        assertThat(cobroService).isInstanceOf(CobroConSaldo.class);
    }

    @Test
    void descuentaDelDisponibleYLoPasaAlRetenidoConUnMovimientoConfirmado() {
        billeteraCon("1500.00");

        Movimiento movimiento = cobroService.cobrar(pagoDeInscripcion("inscripcion-1", MIL));
        entityManager.flush();
        entityManager.clear();

        Movimiento leido = movimientoRepository.findById(movimiento.getId()).orElseThrow();
        assertThat(leido.getEstado()).isEqualTo(EstadoMovimiento.CONFIRMADO);
        assertThat(leido.getTipo()).isEqualTo(TipoMovimiento.INSCRIPCION);
        assertThat(leido.getMonto()).isEqualByComparingTo(MIL);
        assertThat(leido.getSaldoResultante()).isEqualByComparingTo("500.00");
        assertThat(leido.getReferenciaExterna()).isEqualTo("pago-inscripcion-1");
        assertThat(leido.getInscripcion().getId()).isEqualTo(inscripcion.getId());
        assertThat(leido.getTorneo().getId()).isEqualTo(torneo.getId());

        Billetera billetera = billeteraRepository.findByUsuarioId(jugador.getId()).orElseThrow();
        assertThat(billetera.getSaldoDisponible()).isEqualByComparingTo("500.00");
        assertThat(billetera.getSaldoRetenido()).isEqualByComparingTo(MIL);
    }

    @Test
    void conElSaldoJustoLoDejaEnCero() {
        billeteraCon("1000.00");

        cobroService.cobrar(pagoDeInscripcion("inscripcion-1", MIL));

        assertThat(billeteraRepository.findByUsuarioId(jugador.getId()).orElseThrow().getSaldoDisponible())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void sinSaldoSuficienteRechazaConElFaltanteYNoTocaLaBilletera() {
        billeteraCon("400.00");

        SaldoInsuficienteException error = catchThrowableOfType(SaldoInsuficienteException.class,
                () -> cobroService.cobrar(pagoDeInscripcion("inscripcion-1", MIL)));

        assertThat(error.getCodigo()).isEqualTo(CodigoError.SALDO_INSUFICIENTE);
        assertThat(error).isInstanceOf(CobroRechazadoException.class);
        assertThat((BigDecimal) error.getPropiedades().get("faltante")).isEqualByComparingTo("600.00");
        Billetera billetera = billeteraRepository.findByUsuarioId(jugador.getId()).orElseThrow();
        assertThat(billetera.getSaldoDisponible()).isEqualByComparingTo("400.00");
        assertThat(billetera.getSaldoRetenido()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(movimientoRepository.count()).isZero();
    }

    @Test
    void sinBilleteraLaCreaVaciaYRechaza() {
        assertThatThrownBy(() -> cobroService.cobrar(pagoDeInscripcion("inscripcion-1", MIL)))
                .isInstanceOf(SaldoInsuficienteException.class);
        assertThat(billeteraRepository.findByUsuarioId(jugador.getId())).isPresent();
    }

    @Test
    void exigirSaldoDaElFaltanteDelTotalSinCobrar() {
        billeteraCon("1000.00");

        SaldoInsuficienteException error = catchThrowableOfType(SaldoInsuficienteException.class,
                () -> cobroService.exigirSaldo(jugador.getId(), new BigDecimal("1100.00")));

        assertThat((BigDecimal) error.getPropiedades().get("faltante")).isEqualByComparingTo("100.00");
        assertThat(movimientoRepository.count()).isZero();
    }

    @Test
    void exigirSaldoConSaldoSuficienteNoFallaNiCobra() {
        billeteraCon("1100.00");

        cobroService.exigirSaldo(jugador.getId(), new BigDecimal("1100.00"));

        assertThat(billeteraRepository.findByUsuarioId(jugador.getId()).orElseThrow().getSaldoDisponible())
                .isEqualByComparingTo("1100.00");
    }

    @Test
    void conLaMismaClaveDevuelveElMismoMovimientoSinCobrarDeNuevo() {
        billeteraCon("5000.00");
        Movimiento primero = cobroService.cobrar(pagoDeInscripcion("inscripcion-1", MIL));
        entityManager.flush();
        entityManager.clear();

        Movimiento segundo = cobroService.cobrar(pagoDeInscripcion("inscripcion-1", MIL));

        assertThat(segundo.getId()).isEqualTo(primero.getId());
        assertThat(movimientoRepository.count()).isEqualTo(1);
        assertThat(billeteraRepository.findByUsuarioId(jugador.getId()).orElseThrow().getSaldoDisponible())
                .isEqualByComparingTo("4000.00");
    }

    @Test
    void laMismaClaveEnOtroCobroEsUnError() {
        billeteraCon("5000.00");
        cobroService.cobrar(pagoDeInscripcion("inscripcion-1", MIL));

        assertThatThrownBy(() -> cobroService.cobrar(pagoDeInscripcion("inscripcion-1", new BigDecimal("999.00"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void conLaBilleteraBloqueadaRechazaElCobroAunqueTengaSaldo() {
        Billetera billetera = billeteraCon("5000.00");
        billetera.setEstado(EstadoBilletera.BLOQUEADA);
        billeteraRepository.saveAndFlush(billetera);

        assertThatThrownBy(() -> cobroService.cobrar(pagoDeInscripcion("inscripcion-1", MIL)))
                .isInstanceOf(CobroRechazadoException.class)
                .extracting("codigo").isEqualTo(CodigoError.COBRO_RECHAZADO);
        assertThat(movimientoRepository.count()).isZero();
    }

    private Billetera billeteraCon(String disponible) {
        Billetera billetera = new Billetera();
        billetera.setUsuario(jugador);
        billetera.setSaldoDisponible(new BigDecimal(disponible));
        return billeteraRepository.saveAndFlush(billetera);
    }

    private SolicitudCobro pagoDeInscripcion(String clave, BigDecimal monto) {
        return new SolicitudCobro(jugador.getId(), TipoMovimiento.INSCRIPCION, monto, torneo.getId(),
                inscripcion.getId(), clave, "Inscripción al torneo");
    }

    private Usuario usuario(String nombre) {
        Persona persona = new Persona();
        persona.setNombres(nombre);
        persona.setApellidos("Test");
        persona.setEstado(EstadoGeneral.ACTIVO);
        persona = personaRepository.save(persona);

        Usuario usuario = new Usuario();
        usuario.setUsername("b603_" + nombre);
        usuario.setEmail("b603_" + nombre + "@example.com");
        usuario.setEstado(EstadoUsuario.ACTIVO);
        usuario.setEmailVerificado(true);
        usuario.setPersona(persona);
        return usuarioRepository.save(usuario);
    }

    private Torneo torneo(Usuario organizador) {
        Torneo torneo = new Torneo();
        torneo.setOrganizador(organizador);
        torneo.setNombre("Torneo de prueba");
        torneo.setReglamentoUrl("https://reglamento");
        torneo.setDisciplina("Ajedrez");
        torneo.setModalidad(Modalidad.ONLINE);
        torneo.setTipoParticipante(TipoParticipante.INDIVIDUAL);
        torneo.setCupoMaximo(8);
        torneo.setCupoMinimo(2);
        torneo.setValorInscripcion(MIL);
        torneo.setCierreInscripcionEn(Instant.now());
        torneo.setFechaInicio(Instant.now().plus(1, ChronoUnit.DAYS));
        return torneo;
    }
}
