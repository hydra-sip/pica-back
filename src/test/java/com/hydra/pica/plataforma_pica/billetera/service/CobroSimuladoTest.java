package com.hydra.pica.plataforma_pica.billetera.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        CobroSimulado.class,
        JpaAuditingConfig.class,
        SecurityContextCurrentUserProvider.class,
        TestcontainersConfiguration.class
})
class CobroSimuladoTest {

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
    void cobraLaInscripcionConUnMovimientoConfirmadoYCreaLaBilletera() {
        assertThat(billeteraRepository.findByUsuarioId(jugador.getId())).isEmpty();

        Movimiento movimiento = cobroService.cobrar(pagoDeInscripcion("inscripcion-1", MIL));
        entityManager.flush();
        entityManager.clear();

        Movimiento leido = movimientoRepository.findById(movimiento.getId()).orElseThrow();
        assertThat(leido.getEstado()).isEqualTo(EstadoMovimiento.CONFIRMADO);
        assertThat(leido.getTipo()).isEqualTo(TipoMovimiento.INSCRIPCION);
        assertThat(leido.getMonto()).isEqualByComparingTo(MIL);
        assertThat(leido.getReferenciaExterna()).isEqualTo("sim-inscripcion-1");
        assertThat(leido.getInscripcion().getId()).isEqualTo(inscripcion.getId());
        assertThat(leido.getTorneo().getId()).isEqualTo(torneo.getId());

        Billetera billetera = billeteraRepository.findByUsuarioId(jugador.getId()).orElseThrow();
        assertThat(leido.getBilletera().getId()).isEqualTo(billetera.getId());
        assertThat(billetera.getSaldoDisponible()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(leido.getSaldoResultante()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void conLaMismaClaveDevuelveElMismoMovimientoSinCobrarDeNuevo() {
        Movimiento primero = cobroService.cobrar(pagoDeInscripcion("inscripcion-1", MIL));
        entityManager.flush();
        entityManager.clear();

        Movimiento segundo = cobroService.cobrar(pagoDeInscripcion("inscripcion-1", MIL));

        assertThat(segundo.getId()).isEqualTo(primero.getId());
        assertThat(movimientoRepository.count()).isEqualTo(1);
    }

    @Test
    void laMismaClaveEnOtroCobroEsUnError() {
        cobroService.cobrar(pagoDeInscripcion("inscripcion-1", MIL));

        assertThatThrownBy(() -> cobroService.cobrar(pagoDeInscripcion("inscripcion-1", new BigDecimal("999.00"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void conLaBilleteraBloqueadaRechazaElCobro() {
        Billetera billetera = new Billetera();
        billetera.setUsuario(jugador);
        billetera.setEstado(EstadoBilletera.BLOQUEADA);
        billeteraRepository.save(billetera);

        assertThatThrownBy(() -> cobroService.cobrar(pagoDeInscripcion("inscripcion-1", MIL)))
                .isInstanceOf(CobroRechazadoException.class)
                .extracting("codigo").isEqualTo(CodigoError.COBRO_RECHAZADO);
        assertThat(movimientoRepository.count()).isZero();
    }

    @Test
    void unaCompraDeNumerosNoNecesitaInscripcion() {
        Movimiento movimiento = cobroService.cobrar(new SolicitudCobro(jugador.getId(), TipoMovimiento.COMPRA_NUMERO,
                new BigDecimal("300.00"), torneo.getId(), null, "ticket-abc", "Rifa: números 7 y 13"));

        assertThat(movimiento.getEstado()).isEqualTo(EstadoMovimiento.CONFIRMADO);
        assertThat(movimiento.getInscripcion()).isNull();
    }

    @Test
    void rechazaSolicitudesMalArmadas() {
        Long id = jugador.getId();
        assertThatThrownBy(() -> new SolicitudCobro(id, TipoMovimiento.CARGA, MIL, null, null, "c", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SolicitudCobro(id, TipoMovimiento.COMPRA_NUMERO, BigDecimal.ZERO, null, null, "c", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SolicitudCobro(id, TipoMovimiento.INSCRIPCION, MIL, null, null, "c", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SolicitudCobro(id, TipoMovimiento.COMISION, MIL, null, null, " ", null))
                .isInstanceOf(IllegalArgumentException.class);
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
        usuario.setUsername("b14_" + nombre);
        usuario.setEmail("b14_" + nombre + "@example.com");
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
