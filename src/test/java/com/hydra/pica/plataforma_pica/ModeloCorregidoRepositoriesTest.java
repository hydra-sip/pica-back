package com.hydra.pica.plataforma_pica;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import jakarta.persistence.EntityManager;

import com.hydra.pica.plataforma_pica.billetera.domain.Billetera;
import com.hydra.pica.plataforma_pica.billetera.domain.CuentaBancaria;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoValidacion;
import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoCuenta;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.repository.BilleteraRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.CuentaBancariaRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.MovimientoRepository;
import com.hydra.pica.plataforma_pica.common.config.JpaAuditingConfig;
import com.hydra.pica.plataforma_pica.common.security.SecurityContextCurrentUserProvider;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.domain.AlcanceBloqueo;
import com.hydra.pica.plataforma_pica.control.domain.Auditoria;
import com.hydra.pica.plataforma_pica.control.domain.Bloqueo;
import com.hydra.pica.plataforma_pica.control.repository.AuditoriaRepository;
import com.hydra.pica.plataforma_pica.control.repository.BloqueoRepository;
import com.hydra.pica.plataforma_pica.equipo.domain.Equipo;
import com.hydra.pica.plataforma_pica.equipo.domain.UsuarioEquipo;
import com.hydra.pica.plataforma_pica.equipo.repository.EquipoRepository;
import com.hydra.pica.plataforma_pica.equipo.repository.UsuarioEquipoRepository;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.partido.domain.Decision;
import com.hydra.pica.plataforma_pica.partido.domain.Eleccion;
import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.domain.Evidencia;
import com.hydra.pica.plataforma_pica.partido.domain.Juega;
import com.hydra.pica.plataforma_pica.partido.domain.JuegaId;
import com.hydra.pica.plataforma_pica.partido.domain.Lado;
import com.hydra.pica.plataforma_pica.partido.domain.Objecion;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.domain.Pronostico;
import com.hydra.pica.plataforma_pica.partido.domain.PronosticoId;
import com.hydra.pica.plataforma_pica.partido.domain.UsuarioEquipoPartido;
import com.hydra.pica.plataforma_pica.partido.repository.EvidenciaRepository;
import com.hydra.pica.plataforma_pica.partido.repository.JuegaRepository;
import com.hydra.pica.plataforma_pica.partido.repository.ObjecionRepository;
import com.hydra.pica.plataforma_pica.partido.repository.PartidoRepository;
import com.hydra.pica.plataforma_pica.partido.repository.PronosticoRepository;
import com.hydra.pica.plataforma_pica.partido.repository.UsuarioEquipoPartidoRepository;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoNumero;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoReclamo;
import com.hydra.pica.plataforma_pica.rifa.domain.Numero;
import com.hydra.pica.plataforma_pica.rifa.domain.NumeroId;
import com.hydra.pica.plataforma_pica.rifa.domain.PremioRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.ReclamoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Ticket;
import com.hydra.pica.plataforma_pica.rifa.repository.NumeroRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.PremioRifaRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.ReclamoRifaRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.TicketRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.Cancha;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Gasto;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.Premio;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.CanchaRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.GastoRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.PremioRepository;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * B1-2: las entidades de V9 se guardan y se vuelven a leer contra Postgres real. Cada test recorre
 * un tema (torneo, partido, rifa, billetera y control) y limpia la sesión antes de leer.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        SecurityContextCurrentUserProvider.class,
        TestcontainersConfiguration.class
})
class ModeloCorregidoRepositoriesTest {

    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private TorneoRepository torneoRepository;
    @Autowired private CanchaRepository canchaRepository;
    @Autowired private PremioRepository premioRepository;
    @Autowired private GastoRepository gastoRepository;
    @Autowired private EquipoRepository equipoRepository;
    @Autowired private UsuarioEquipoRepository usuarioEquipoRepository;
    @Autowired private InscripcionRepository inscripcionRepository;
    @Autowired private PartidoRepository partidoRepository;
    @Autowired private JuegaRepository juegaRepository;
    @Autowired private UsuarioEquipoPartidoRepository usuarioEquipoPartidoRepository;
    @Autowired private EvidenciaRepository evidenciaRepository;
    @Autowired private ObjecionRepository objecionRepository;
    @Autowired private PronosticoRepository pronosticoRepository;
    @Autowired private BilleteraRepository billeteraRepository;
    @Autowired private MovimientoRepository movimientoRepository;
    @Autowired private CuentaBancariaRepository cuentaBancariaRepository;
    @Autowired private RifaRepository rifaRepository;
    @Autowired private PremioRifaRepository premioRifaRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private NumeroRepository numeroRepository;
    @Autowired private ReclamoRifaRepository reclamoRifaRepository;
    @Autowired private BloqueoRepository bloqueoRepository;
    @Autowired private AuditoriaRepository auditoriaRepository;

    private Usuario organizador;
    private Usuario arbitro;
    private Usuario jugador;
    private Usuario rival;

    @BeforeEach
    void usuarios() {
        organizador = usuario("organizador");
        arbitro = usuario("arbitro");
        jugador = usuario("jugador");
        rival = usuario("rival");
    }

    @Test
    void torneoConCanchasArbitrosPremiosYGastos() {
        Cancha cancha = new Cancha();
        cancha.setNombre("Cancha 1");
        cancha.setSede("Club");
        canchaRepository.save(cancha);

        Torneo torneo = torneo(TipoParticipante.INDIVIDUAL);
        torneo.getCanchas().add(cancha);
        torneo.getArbitros().add(arbitro);
        torneoRepository.saveAndFlush(torneo);

        Premio premio = new Premio(torneo, 1);
        premio.setPct(new BigDecimal("100.00"));
        premioRepository.save(premio);

        Gasto gasto = new Gasto(torneo, 1);
        gasto.setBeneficiario(organizador);
        gasto.setComprobante("https://comprobante");
        gastoRepository.saveAndFlush(gasto);
        entityManager.clear();

        Torneo leido = torneoRepository.findById(torneo.getId()).orElseThrow();
        assertThat(leido.getEstado()).isEqualTo(EstadoTorneo.BORRADOR);
        assertThat(leido.getMoneda()).isEqualTo("ARS");
        assertThat(leido.getCreadoEn()).isNotNull();
        assertThat(leido.getCanchas()).extracting(Cancha::getNombre).containsExactly("Cancha 1");
        assertThat(leido.getArbitros()).extracting(Usuario::getId).containsExactly(arbitro.getId());
        assertThat(premioRepository.findById(premio.getId()).orElseThrow().getPct()).isEqualByComparingTo("100");
        assertThat(gastoRepository.findById(gasto.getId()).orElseThrow().getMontoAbonado()).isEqualByComparingTo("0");
    }

    @Test
    void partidoConSusPartesObjecionEvidenciaYPronostico() {
        Torneo torneo = torneo(TipoParticipante.INDIVIDUAL);
        torneo.getArbitros().add(arbitro);
        // Las filas de torneo_arbitro salen al final del flush: sin este flush, el INSERT del partido
        // llega antes y la FK compuesta lo rechaza
        torneoRepository.saveAndFlush(torneo);
        Inscripcion local = inscripcion(torneo, jugador);
        Inscripcion visitante = inscripcion(torneo, rival);

        Partido partido = new Partido();
        partido.setTorneo(torneo);
        partido.setArbitro(arbitro);
        partido.setRonda(1);
        partido.setPosicion(1);
        partidoRepository.save(partido);
        juegaRepository.save(new Juega(partido, local, Lado.LOCAL));
        Juega juegaVisitante = new Juega(partido, visitante, Lado.VISITANTE);
        juegaVisitante.setPuntaje(2);
        juegaRepository.saveAndFlush(juegaVisitante);

        Evidencia evidencia = new Evidencia();
        evidencia.setPartido(partido);
        evidencia.setInscripcion(visitante);
        evidencia.setUrl("https://foto");
        evidencia.setTipoContenido("image/jpeg");
        evidencia.setTamanioBytes(1024L);
        evidencia.setHash("a".repeat(64));
        evidenciaRepository.save(evidencia);

        Objecion objecion = new Objecion(partido);
        objecion.setInscripcion(visitante);
        objecion.setMotivo("El resultado está mal cargado");
        objecion.setPuntajeLocal(1);
        objecion.setPuntajeVisitante(2);
        objecion.setResueltaPor(arbitro);
        objecion.setDecision(Decision.CORREGIDO);
        objecion.setJustificacion("Se ve en la foto");
        objecion.setEvidencia(evidencia);
        objecion.setLaudoHash("b".repeat(64));
        objecion.setResueltaEn(Instant.now());
        objecionRepository.save(objecion);

        pronosticoRepository.saveAndFlush(new Pronostico(organizador, partido, Eleccion.EMPATE));
        entityManager.clear();

        assertThat(partidoRepository.findById(partido.getId()).orElseThrow().getEstado()).isEqualTo(EstadoPartido.PROGRAMADO);
        assertThat(juegaRepository.findById(new JuegaId(partido.getId(), visitante.getId())).orElseThrow())
                .extracting(Juega::getLado, Juega::getPuntaje)
                .containsExactly(Lado.VISITANTE, 2);
        Objecion leida = objecionRepository.findById(partido.getId()).orElseThrow();
        assertThat(leida.getDecision()).isEqualTo(Decision.CORREGIDO);
        assertThat(leida.getEvidencia().getId()).isEqualTo(evidencia.getId());
        assertThat(evidenciaRepository.findById(evidencia.getId()).orElseThrow().getCreadoPor()).isNotNull();
        assertThat(pronosticoRepository.findById(new PronosticoId(organizador.getId(), partido.getId())).orElseThrow()
                .getEleccion()).isEqualTo(Eleccion.EMPATE);
        assertThat(jdbc.queryForObject("SELECT eleccion FROM pronostico WHERE partido_id = ?", String.class, partido.getId()))
                .isEqualTo("X");
    }

    @Test
    void equipoInscriptoYQuienesJugaronElPartido() {
        Torneo torneo = torneo(TipoParticipante.EQUIPO);
        torneo.getArbitros().add(arbitro);
        // Las filas de torneo_arbitro salen al final del flush: sin este flush, el INSERT del partido
        // llega antes y la FK compuesta lo rechaza
        torneoRepository.saveAndFlush(torneo);

        Equipo equipo = new Equipo();
        equipo.setCapitan(jugador);
        equipo.setNombre("Los Pibes");
        equipoRepository.save(equipo);
        usuarioEquipoRepository.save(new UsuarioEquipo(jugador, equipo));

        Inscripcion inscripcion = new Inscripcion();
        inscripcion.setTorneo(torneo);
        inscripcion.setEquipo(equipo);
        inscripcion.setReglamentoAceptadoEn(Instant.now());
        inscripcionRepository.save(inscripcion);

        Partido partido = new Partido();
        partido.setTorneo(torneo);
        partido.setArbitro(arbitro);
        partido.setRonda(1);
        partido.setPosicion(1);
        partidoRepository.save(partido);
        usuarioEquipoPartidoRepository.saveAndFlush(new UsuarioEquipoPartido(partido, jugador, equipo));
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        entityManager.clear();

        Inscripcion leida = inscripcionRepository.findById(inscripcion.getId()).orElseThrow();
        assertThat(leida.getEstado()).isEqualTo(EstadoInscripcion.BORRADOR);
        assertThat(leida.getUsuario()).isNull();
        assertThat(leida.getEquipo().getNombre()).isEqualTo("Los Pibes");
        assertThat(usuarioEquipoPartidoRepository.findAll()).hasSize(1);
    }

    @Test
    void rifaConPremioTicketNumeroVendidoYReclamo() {
        Torneo torneo = torneoRepository.save(torneo(TipoParticipante.INDIVIDUAL));
        Rifa rifa = new Rifa();
        rifa.setOrganizador(organizador);
        rifa.setTorneo(torneo);
        rifa.setTitulo("Rifa del torneo");
        rifa.setNumeroDesde(1);
        rifa.setNumeroHasta(100);
        rifa.setPrecioNumero(new BigDecimal("500.00"));
        rifa.setVentaDesde(Instant.now());
        rifa.setVentaHasta(Instant.now().plus(7, ChronoUnit.DAYS));
        rifa.setTerminosAceptadosEn(Instant.now());
        rifaRepository.save(rifa);

        PremioRifa premio = new PremioRifa(rifa, 1);
        premio.setPct(100);
        premio.setDescripcion("Una camiseta");
        premioRifaRepository.save(premio);

        Movimiento pago = movimiento(TipoMovimiento.COMPRA_NUMERO, null);
        Ticket ticket = new Ticket();
        ticket.setCodigo("T-0001");
        ticket.setRifa(rifa);
        ticket.setComprador(jugador);
        ticket.setMovimiento(pago);
        ticket.setMonto(new BigDecimal("500.00"));
        ticket.setComision(new BigDecimal("25.00"));
        ticket.setClaveIdempotencia("clave-1");
        ticketRepository.save(ticket);

        // V13: el número se inserta al elegirlo, reservado mientras se paga, y al pagar queda vendido
        // con su ticket; el ganador queda en el premio
        Numero elegido = numeroRepository.saveAndFlush(
                Numero.reservado(rifa, 7, jugador));
        elegido.vender(ticket);
        numeroRepository.save(elegido);
        premio.setNumeroGanador(7);
        premioRifaRepository.save(premio);

        ReclamoRifa reclamo = new ReclamoRifa();
        reclamo.setRifa(rifa);
        reclamo.setUsuario(jugador);
        reclamo.setMotivo("No vi el sorteo");
        reclamoRifaRepository.saveAndFlush(reclamo);
        entityManager.clear();

        Numero vendido = numeroRepository.findById(new NumeroId(rifa.getId(), 7)).orElseThrow();
        assertThat(vendido.getEstado()).isEqualTo(EstadoNumero.VENDIDO);
        assertThat(vendido.getTicket().getCodigo()).isEqualTo("T-0001");
        assertThat(vendido.getReservadoPor()).isNull();
        assertThat(numeroRepository.findById(new NumeroId(rifa.getId(), 1))).isEmpty();
        assertThat(premioRifaRepository.buscarPorRifa(rifa.getId()))
                .extracting(PremioRifa::getNumeroGanador).containsExactly(7);
        assertThat(ticketRepository.findById(ticket.getId()).orElseThrow().getMovimiento().getId()).isEqualTo(pago.getId());
        assertThat(reclamoRifaRepository.findById(reclamo.getId()).orElseThrow())
                .extracting(ReclamoRifa::getEstado, r -> r.getCreadoEn() != null)
                .containsExactly(EstadoReclamo.PENDIENTE, true);

        // Que otro reserve el 7 falla por la PK: save de un Numero nuevo hace INSERT, no merge.
        entityManager.clear();
        Numero repetido = Numero.reservado(rifaRepository.getReferenceById(rifa.getId()), 7,
                usuarioRepository.getReferenceById(rival.getId()));
        assertThatThrownBy(() -> numeroRepository.saveAndFlush(repetido))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void billeteraConPagoDeInscripcionYCuentaBancaria() {
        Torneo torneo = torneoRepository.save(torneo(TipoParticipante.INDIVIDUAL));
        Inscripcion inscripcion = inscripcion(torneo, jugador);
        Movimiento pago = movimiento(TipoMovimiento.INSCRIPCION, inscripcion);

        CuentaBancaria cuenta = new CuentaBancaria();
        cuenta.setUsuario(jugador);
        cuenta.setTipo(TipoCuenta.ALIAS);
        cuenta.setValor("jugador.pica");
        cuenta.setTitular("Jugador Test");
        cuenta.setCuitCuil("20-12345678-9");
        cuentaBancariaRepository.saveAndFlush(cuenta);
        entityManager.clear();

        Movimiento leido = movimientoRepository.findById(pago.getId()).orElseThrow();
        assertThat(leido.getEstado()).isEqualTo(EstadoMovimiento.PENDIENTE);
        assertThat(leido.getInscripcion().getId()).isEqualTo(inscripcion.getId());
        assertThat(leido.getBilletera().getSaldoDisponible()).isEqualByComparingTo("0");
        assertThat(cuentaBancariaRepository.findById(cuenta.getId()).orElseThrow().getEstadoValidacion())
                .isEqualTo(EstadoValidacion.PENDIENTE);
    }

    @Test
    void bloqueoYAuditoriaConDetalleJson() {
        Bloqueo bloqueo = new Bloqueo();
        bloqueo.setUsuario(jugador);
        bloqueo.setAlcance(AlcanceBloqueo.USUARIO);
        bloqueo.setMotivo("Patrón de red sospechoso");
        bloqueoRepository.save(bloqueo);

        Auditoria auditoria = new Auditoria();
        auditoria.setUsuario(jugador);
        auditoria.setAccion(AccionAuditoria.BLOQUEO);
        auditoria.setEntidad("usuario");
        auditoria.setEntidadId(String.valueOf(jugador.getId()));
        auditoria.setIp("10.0.0.1");
        auditoria.setDetalle(Map.of("alcance", "USUARIO", "dias", 7));
        auditoriaRepository.saveAndFlush(auditoria);
        entityManager.clear();

        Bloqueo leido = bloqueoRepository.findById(bloqueo.getId()).orElseThrow();
        assertThat(leido.getDesde()).isNotNull();
        assertThat(leido.getHasta()).isNull();
        assertThat(auditoriaRepository.findById(auditoria.getId()).orElseThrow().getDetalle())
                .containsEntry("alcance", "USUARIO")
                .containsEntry("dias", 7);
    }

    private Usuario usuario(String nombre) {
        Persona persona = new Persona();
        persona.setNombres(nombre);
        persona.setApellidos("Test");
        persona.setEstado(EstadoGeneral.ACTIVO);
        persona = personaRepository.save(persona);

        Usuario usuario = new Usuario();
        usuario.setUsername("b12_" + nombre);
        usuario.setEmail("b12_" + nombre + "@example.com");
        usuario.setEstado(EstadoUsuario.ACTIVO);
        usuario.setEmailVerificado(true);
        usuario.setPersona(persona);
        return usuarioRepository.save(usuario);
    }

    private Torneo torneo(TipoParticipante tipo) {
        Torneo torneo = new Torneo();
        torneo.setOrganizador(organizador);
        torneo.setNombre("Torneo de prueba");
        torneo.setReglamentoUrl("https://reglamento");
        torneo.setDisciplina("Ajedrez");
        torneo.setModalidad(Modalidad.ONLINE);
        torneo.setTipoParticipante(tipo);
        torneo.setCupoMaximo(8);
        torneo.setCupoMinimo(2);
        torneo.setTamanioEquipo(tipo == TipoParticipante.EQUIPO ? 5 : null);
        torneo.setValorInscripcion(new BigDecimal("1000.00"));
        torneo.setPctPozoCompetidores(new BigDecimal("50"));
        torneo.setPctPremioRifa(new BigDecimal("10"));
        torneo.setCierreInscripcionEn(Instant.now());
        torneo.setFechaInicio(Instant.now().plus(1, ChronoUnit.DAYS));
        return torneo;
    }

    private Inscripcion inscripcion(Torneo torneo, Usuario usuario) {
        Inscripcion inscripcion = new Inscripcion();
        inscripcion.setTorneo(torneo);
        inscripcion.setUsuario(usuario);
        inscripcion.setReglamentoAceptadoEn(Instant.now());
        return inscripcionRepository.save(inscripcion);
    }

    private Movimiento movimiento(TipoMovimiento tipo, Inscripcion inscripcion) {
        Billetera billetera = new Billetera();
        billetera.setUsuario(jugador);
        billeteraRepository.save(billetera);

        Movimiento movimiento = new Movimiento();
        movimiento.setBilletera(billetera);
        movimiento.setInscripcion(inscripcion);
        movimiento.setTipo(tipo);
        movimiento.setMonto(new BigDecimal("500.00"));
        movimiento.setSaldoResultante(BigDecimal.ZERO);
        return movimientoRepository.save(movimiento);
    }
}
