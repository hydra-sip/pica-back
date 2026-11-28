package com.hydra.pica.plataforma_pica.control.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.hydra.pica.plataforma_pica.EscenarioTorneo;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.billetera.domain.CuentaBancaria;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoValidacion;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoCuenta;
import com.hydra.pica.plataforma_pica.billetera.dto.CrearCuentaBancariaRequest;
import com.hydra.pica.plataforma_pica.billetera.event.CuentaBancariaRegistrada;
import com.hydra.pica.plataforma_pica.billetera.event.MovimientoBilleteraRegistrado;
import com.hydra.pica.plataforma_pica.billetera.repository.CuentaBancariaRepository;
import com.hydra.pica.plataforma_pica.billetera.service.CargaService;
import com.hydra.pica.plataforma_pica.billetera.service.CuentaBancariaService;
import com.hydra.pica.plataforma_pica.billetera.service.RetiroService;
import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.common.security.JwtService;
import com.hydra.pica.plataforma_pica.control.domain.AlertaFraude;
import com.hydra.pica.plataforma_pica.control.domain.ClaveParametro;
import com.hydra.pica.plataforma_pica.control.domain.EstadoAlertaFraude;
import com.hydra.pica.plataforma_pica.control.domain.ReglaFraude;
import com.hydra.pica.plataforma_pica.control.dto.RegistroAuditoria;
import com.hydra.pica.plataforma_pica.control.repository.AlertaFraudeRepository;
import com.hydra.pica.plataforma_pica.control.service.fraude.ReglaCuentasCompartidas;
import com.hydra.pica.plataforma_pica.control.service.fraude.ReglaDeFraude;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.partido.dto.ObjecionRequest;
import com.hydra.pica.plataforma_pica.partido.dto.ResultadoRequest;
import com.hydra.pica.plataforma_pica.partido.event.ResultadoObjetado;
import com.hydra.pica.plataforma_pica.partido.service.BracketService;
import com.hydra.pica.plataforma_pica.partido.service.EvidenciaService;
import com.hydra.pica.plataforma_pica.partido.service.ResultadoService;
import com.hydra.pica.plataforma_pica.rifa.dto.PremioRifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaRequest;
import com.hydra.pica.plataforma_pica.rifa.event.NumerosVendidos;
import com.hydra.pica.plataforma_pica.rifa.service.RifaService;
import com.hydra.pica.plataforma_pica.rifa.service.VentaRifaService;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.PermisoArbitro;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.dto.DesignarArbitroRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.InformacionTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.SolicitudCancelacion;
import com.hydra.pica.plataforma_pica.torneo.event.CancelacionResuelta;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.torneo.service.ArbitrosTorneoService;
import com.hydra.pica.plataforma_pica.torneo.service.CancelacionTorneoService;
import com.hydra.pica.plataforma_pica.torneo.service.TorneoService;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Modulo;
import com.hydra.pica.plataforma_pica.user.domain.Permiso;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PermisoRepository;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.RolRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integración completa de detección de conductas fraudulentas y panel admin (HU-705, PICA-416) contra PostgreSQL real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, EscenarioTorneo.class})
@RecordApplicationEvents
@Transactional
class AlertaFraudeIntegracionTest {

    private static final String CBU_VALIDO = "2850590940090418135201";
    private static final byte[] JPG = jpgDePrueba();
    private static final Instant MANIANA = Instant.now().plus(1, ChronoUnit.DAYS);
    private static final Instant EN_UNA_SEMANA = Instant.now().plus(7, ChronoUnit.DAYS);
    private static final Instant EN_DOS_SEMANAS = Instant.now().plus(14, ChronoUnit.DAYS);

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private DeteccionFraudeService deteccionFraudeService;
    @Autowired private List<ReglaDeFraude> reglas;
    @Autowired private ReglaCuentasCompartidas reglaCuentasCompartidas;
    @Autowired private AlertaFraudeRepository alertaFraudeRepository;
    @Autowired private ParametrosPlataforma parametrosPlataforma;
    @Autowired private LibroAuditoriaService libroAuditoriaService;
    @Autowired private PermisoRepository permisoRepository;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private RifaService rifaService;
    @Autowired private VentaRifaService ventaRifaService;
    @Autowired private CargaService cargaService;
    @Autowired private CuentaBancariaService cuentaBancariaService;
    @Autowired private CuentaBancariaRepository cuentaBancariaRepository;
    @Autowired private RetiroService retiroService;
    @Autowired private TorneoService torneoService;
    @Autowired private ArbitrosTorneoService arbitrosTorneoService;
    @Autowired private InscripcionRepository inscripcionRepository;
    @Autowired private TorneoRepository torneoRepository;
    @Autowired private BracketService bracketService;
    @Autowired private EvidenciaService evidenciaService;
    @Autowired private ResultadoService resultadoService;
    @Autowired private CancelacionTorneoService cancelacionTorneoService;
    @Autowired private EscenarioTorneo escenario;
    @Autowired private ApplicationEvents eventos;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @MockitoBean private EmailService emailService;

    private Usuario soporte;
    private Usuario participante;
    private Usuario organizador;
    private String tokenSoporte;
    private String tokenParticipante;

    @BeforeEach
    void prepararUsuarios() {
        soporte = escenario.usuario("soporte_fraude", "SOPORTE");
        participante = escenario.usuario("part_fraude", "PARTICIPANTE");
        organizador = escenario.usuario("org_fraude", "ORGANIZADOR");
        tokenSoporte = token(soporte, "SOPORTE");
        tokenParticipante = token(participante, "PARTICIPANTE");
    }

    @Test
    @DisplayName("V60 crea el permiso ALERTA_FRAUDE_REVISAR en AUDITORIA y los 7 parámetros FRAUDE_*")
    void permisoSeedYParametrosIniciales() {
        Permiso permiso = permisoRepository.findByCodigo("ALERTA_FRAUDE_REVISAR").orElseThrow();
        assertThat(permiso.getModulo()).isEqualTo(Modulo.AUDITORIA);

        for (String rolNombre : List.of("SOPORTE", "ADMINISTRADOR", "SUPER_USUARIO")) {
            assertThat(rolRepository.findByNombre(rolNombre).orElseThrow().getPermisos())
                    .extracting(Permiso::getCodigo)
                    .contains("ALERTA_FRAUDE_REVISAR");
        }
        assertThat(parametrosPlataforma.cantidad(ClaveParametro.FRAUDE_RIFA_NUMEROS_MAX)).isEqualTo(10);
        assertThat(parametrosPlataforma.minutos(ClaveParametro.FRAUDE_RIFA_VENTANA_MIN).toMinutes()).isEqualTo(30);
        assertThat(parametrosPlataforma.cantidad(ClaveParametro.FRAUDE_CARRUSEL_OPERACIONES_MIN)).isEqualTo(4);
        assertThat(parametrosPlataforma.minutos(ClaveParametro.FRAUDE_CARRUSEL_VENTANA_MIN).toMinutes()).isEqualTo(60);
        assertThat(parametrosPlataforma.cantidad(ClaveParametro.FRAUDE_CANCELACIONES_MIN)).isEqualTo(2);
        assertThat(parametrosPlataforma.cantidad(ClaveParametro.FRAUDE_OBJECIONES_MIN)).isEqualTo(3);
        assertThat(parametrosPlataforma.cantidad(ClaveParametro.FRAUDE_CUENTAS_USUARIOS_MIN)).isEqualTo(2);
    }

    @Test
    @DisplayName("Regla 1 (COMPRA_MASIVA_RIFA): compra normal no alerta; superar el umbral en la ventana sí y es idempotente")
    void reglaCompraMasivaEnRifa() {
        jdbc.update("UPDATE parametro_plataforma SET valor = 2 WHERE clave = 'FRAUDE_RIFA_NUMEROS_MAX'");
        long torneoId = crearTorneoAbierto(organizador.getId(), "Torneo Rifa Fraude");
        RifaDetalle rifa = rifaService.crear(organizador.getId(), new RifaRequest(
                "Rifa Controlada", null, 1, 20, new BigDecimal("500"),
                MANIANA, EN_UNA_SEMANA, EN_UNA_SEMANA.plus(1, ChronoUnit.DAYS), torneoId, BigDecimal.TEN));
        rifaService.reemplazarPremios(organizador.getId(), rifa.id(),
                List.of(new PremioRifaRequest(BigDecimal.valueOf(90), null)));
        rifaService.publicar(organizador.getId(), rifa.id());
        jdbc.update("UPDATE rifa SET venta_desde = now() - interval '1 hour' WHERE id = ?", rifa.id());
        entityManager.clear();

        cargaService.acreditar(participante.getId(), new BigDecimal("5000.00"),
                "saldo-rifa-" + rifa.id(), "Saldo para rifa");

        // Actividad dentro de parámetros normales (2 <= max 2): no genera alerta
        ventaRifaService.reservar(participante.getId(), rifa.id(), List.of(1, 2));
        ventaRifaService.comprar(participante.getId(), rifa.id(), List.of(1, 2), "normal-" + rifa.id());
        entityManager.flush();

        assertThat(deteccionFraudeService.evaluarCompraRifa(rifa.id(), participante.getId())).isFalse();
        assertThat(alertaFraudeRepository.findAll()).isEmpty();

        // Compra adicional supera el límite (3 > 2): genera alerta
        ventaRifaService.reservar(participante.getId(), rifa.id(), List.of(3));
        ventaRifaService.comprar(participante.getId(), rifa.id(), List.of(3), "exceso-" + rifa.id());
        entityManager.flush();

        assertThat(eventos.stream(NumerosVendidos.class)).hasSize(2);
        assertThat(deteccionFraudeService.evaluarCompraRifa(rifa.id(), participante.getId())).isTrue();
        // Idempotencia: no duplica alerta pendiente
        assertThat(deteccionFraudeService.evaluarCompraRifa(rifa.id(), participante.getId())).isFalse();
        assertThat(deteccionFraudeService.evaluarTodo()).isZero();

        AlertaFraude alerta = alertaFraudeRepository.findAll().getFirst();
        assertThat(alerta.getRegla()).isEqualTo(ReglaFraude.COMPRA_MASIVA_RIFA);
        assertThat(alerta.getEstado()).isEqualTo(EstadoAlertaFraude.PENDIENTE);
        assertThat(alerta.getUsuario().getId()).isEqualTo(participante.getId());
        assertThat(alerta.getRifa().getId()).isEqualTo(rifa.id());
        assertThat(alerta.getTorneo().getId()).isEqualTo(torneoId);
        assertThat(alerta.getDatos()).containsEntry("numerosComprados", 3L).containsEntry("umbralNumeros", 2);
    }

    @Test
    @DisplayName("Regla 2 (CARRUSEL_CARGA_RETIRO): cargas y retiros normales no alertan; alcanzar umbral sí")
    void reglaCarruselCargaRetiro() {
        Usuario usuario = usuarioConDni("carrusel_u", "35111222");
        cuentaBancariaService.crear(usuario.getId(), new CrearCuentaBancariaRequest(
                TipoCuenta.CBU, CBU_VALIDO, "Carrusel User", "20-35111222-9"));
        Long cuentaId = cuentaBancariaRepository.findByUsuarioIdOrderByCreadoEnDescIdDesc(usuario.getId())
                .getFirst().getId();

        // Actividad normal: 1 carga + 1 retiro (2 < 4) -> no genera alerta
        cargaService.acreditar(usuario.getId(), new BigDecimal("5000.00"), "car-1", "Carga 1");
        retiroService.solicitar(usuario.getId(), cuentaId, new BigDecimal("1000.00"));
        entityManager.flush();

        assertThat(deteccionFraudeService.evaluarCarruselBilletera(usuario.getId())).isFalse();
        assertThat(alertaFraudeRepository.findAll()).isEmpty();

        // Completa carrusel (2 cargas + 2 retiros = 4 >= 4) -> genera alerta
        cargaService.acreditar(usuario.getId(), new BigDecimal("5000.00"), "car-2", "Carga 2");
        retiroService.solicitar(usuario.getId(), cuentaId, new BigDecimal("1000.00"));
        entityManager.flush();

        assertThat(eventos.stream(MovimientoBilleteraRegistrado.class)).hasSize(4);
        assertThat(deteccionFraudeService.evaluarCarruselBilletera(usuario.getId())).isTrue();
        assertThat(deteccionFraudeService.evaluarCarruselBilletera(usuario.getId())).isFalse();

        AlertaFraude alerta = alertaFraudeRepository.findAll().getFirst();
        assertThat(alerta.getRegla()).isEqualTo(ReglaFraude.CARRUSEL_CARGA_RETIRO);
        assertThat(alerta.getUsuario().getId()).isEqualTo(usuario.getId());
        assertThat(alerta.getDatos())
                .containsEntry("cargas", 2L)
                .containsEntry("retiros", 2L)
                .containsEntry("totalOperaciones", 4L);
    }

    @Test
    @DisplayName("Regla 3 (CANCELACIONES_REITERADAS): 1 cancelación no alerta; 2 o más alertan al organizador una sola vez")
    void reglaCancelacionesReiteradas() {
        Long torneo1 = crearTorneoAbierto(organizador.getId(), "Torneo Cancel 1");
        Long torneo2 = crearTorneoAbierto(organizador.getId(), "Torneo Cancel 2");
        Long torneo3 = crearTorneoAbierto(organizador.getId(), "Torneo Cancel 3");

        cancelacionTorneoService.cancelar(organizador.getId(), torneo1, new SolicitudCancelacion("Motivo 1", List.of()));
        entityManager.flush();
        assertThat(deteccionFraudeService.evaluarCancelacionesOrganizador(torneo1)).isFalse();

        cancelacionTorneoService.cancelar(organizador.getId(), torneo2, new SolicitudCancelacion("Motivo 2", List.of()));
        entityManager.flush();
        assertThat(eventos.stream(CancelacionResuelta.class)).hasSize(2);
        assertThat(deteccionFraudeService.evaluarCancelacionesOrganizador(torneo2)).isTrue();

        cancelacionTorneoService.cancelar(organizador.getId(), torneo3, new SolicitudCancelacion("Motivo 3", List.of()));
        entityManager.flush();
        assertThat(deteccionFraudeService.evaluarCancelacionesOrganizador(torneo3)).isFalse();

        List<AlertaFraude> alertas = alertaFraudeRepository.findAll();
        assertThat(alertas).singleElement().satisfies(a -> {
            assertThat(a.getRegla()).isEqualTo(ReglaFraude.CANCELACIONES_REITERADAS);
            assertThat(a.getUsuario().getId()).isEqualTo(organizador.getId());
            assertThat(a.getTorneo().getId()).isEqualTo(torneo2);
            assertThat(a.getDatos()).containsEntry("torneosCancelados", 2);
        });
    }

    @Test
    @DisplayName("Regla 4 (OBJECIONES_ABUSIVAS): debajo del mínimo no alerta; al alcanzar umbral sí")
    void reglaObjecionesAbusivas() {
        Usuario arbitro = escenario.usuario("juez_obj_fraude", "ARBITRO");
        Usuario rival = escenario.usuario("rival_obj_fraude", "PARTICIPANTE");

        long partido1 = crearPartidoYObjetar(arbitro.getId(), rival.getId(), participante.getId(), "Copa Obj 1");
        long partido2 = crearPartidoYObjetar(arbitro.getId(), rival.getId(), participante.getId(), "Copa Obj 2");
        assertThat(deteccionFraudeService.evaluarObjecionesUsuario(null, partido1)).isFalse();
        assertThat(deteccionFraudeService.evaluarObjecionesUsuario(null, partido2)).isFalse();

        long partido3 = crearPartidoYObjetar(arbitro.getId(), rival.getId(), participante.getId(), "Copa Obj 3");
        assertThat(eventos.stream(ResultadoObjetado.class)).hasSize(3);
        assertThat(deteccionFraudeService.evaluarObjecionesUsuario(null, partido3)).isTrue();
        assertThat(deteccionFraudeService.evaluarObjecionesUsuario(null, partido3)).isFalse();

        AlertaFraude alerta = alertaFraudeRepository.findAll().getFirst();
        assertThat(alerta.getRegla()).isEqualTo(ReglaFraude.OBJECIONES_ABUSIVAS);
        assertThat(alerta.getUsuario().getId()).isEqualTo(participante.getId());
        assertThat(alerta.getTorneo()).isNotNull();
        assertThat(alerta.getDatos()).containsEntry("totalObjeciones", 3L);
    }

    @Test
    @DisplayName("Regla 5 (CUENTAS_COMPARTIDAS): un usuario no alerta; dos usuarios con mismo CBU/Alias sí")
    void reglaCuentasCompartidas() {
        Usuario u1 = usuarioConDni("comparte_1", "36111222");
        Usuario u2 = usuarioConDni("comparte_2", "37222333");

        cuentaBancariaService.crear(u1.getId(), new CrearCuentaBancariaRequest(
                TipoCuenta.CBU, CBU_VALIDO, "Usuario Uno", "20-36111222-9"));
        assertThat(eventos.stream(CuentaBancariaRegistrada.class)).hasSize(1);
        assertThat(deteccionFraudeService.evaluarCuentaCompartida(u1.getId(), CBU_VALIDO)).isFalse();

        CuentaBancaria compartida = new CuentaBancaria();
        compartida.setUsuario(u2);
        compartida.setTipo(TipoCuenta.CBU);
        compartida.setValor(CBU_VALIDO);
        compartida.setTitular("Usuario Uno");
        compartida.setCuitCuil("20-36111222-9");
        compartida.setEstadoValidacion(EstadoValidacion.RECHAZADA);
        cuentaBancariaRepository.saveAndFlush(compartida);

        assertThat(deteccionFraudeService.evaluarCuentaCompartida(u2.getId(), CBU_VALIDO)).isTrue();
        assertThat(deteccionFraudeService.evaluarCuentaCompartida(u1.getId(), CBU_VALIDO)).isFalse();
        assertThat(deteccionFraudeService.evaluarTodo()).isZero();

        AlertaFraude alerta = alertaFraudeRepository.findAll().getFirst();
        assertThat(alerta.getRegla()).isEqualTo(ReglaFraude.CUENTAS_COMPARTIDAS);
        assertThat(alerta.getUsuario().getId()).isEqualTo(u2.getId());
        assertThat(alerta.getMotivo()).contains(CBU_VALIDO);
        assertThat(alerta.getDatos()).containsEntry("valor", CBU_VALIDO).containsEntry("cantidadUsuarios", 2);
    }

    @Test
    @DisplayName("Cada regla corre sola: la pasada de una no crea alertas de las otras")
    void cadaReglaCorreSola() {
        Usuario u1 = usuarioConDni("sola_1", "36111223");
        Usuario u2 = usuarioConDni("sola_2", "37222334");
        for (Usuario u : List.of(u1, u2)) {
            CuentaBancaria cuenta = new CuentaBancaria();
            cuenta.setUsuario(u);
            cuenta.setTipo(TipoCuenta.CBU);
            cuenta.setValor(CBU_VALIDO);
            cuenta.setTitular("Titular");
            cuenta.setCuitCuil("20-36111223-9");
            cuenta.setEstadoValidacion(EstadoValidacion.RECHAZADA);
            cuentaBancariaRepository.saveAndFlush(cuenta);
        }

        assertThat(reglas).hasSize(5);
        for (ReglaDeFraude regla : reglas) {
            if (!(regla instanceof ReglaCuentasCompartidas)) {
                assertThat(regla.evaluarTodo()).as(regla.getClass().getSimpleName()).isZero();
            }
        }
        assertThat(alertaFraudeRepository.count()).isZero();

        assertThat(reglaCuentasCompartidas.evaluarTodo()).isEqualTo(1);
        assertThat(reglaCuentasCompartidas.evaluarTodo()).isZero();
        assertThat(alertaFraudeRepository.findAll()).singleElement()
                .extracting(AlertaFraude::getRegla).isEqualTo(ReglaFraude.CUENTAS_COMPARTIDAS);
    }

    @Test
    @DisplayName("Endpoints /admin/alertas-fraude: permisos, filtros, detalle, nota obligatoria y auditoría")
    void endpointsListadoDetalleRevisionYAuditoria() throws Exception {
        Long torneoId = crearTorneoAbierto(organizador.getId(), "Torneo Panel Fraude");
        RifaDetalle rifa = rifaService.crear(organizador.getId(), new RifaRequest(
                "Rifa Panel", null, 1, 20, new BigDecimal("100"),
                MANIANA, EN_UNA_SEMANA, EN_UNA_SEMANA.plus(1, ChronoUnit.DAYS), torneoId, BigDecimal.TEN));

        AlertaFraude alerta1 = guardarAlerta(ReglaFraude.COMPRA_MASIVA_RIFA, participante,
                torneoRepository.getReferenceById(torneoId), null, "Compra masiva en rifa",
                Map.of("numerosComprados", 15), EstadoAlertaFraude.PENDIENTE, Instant.now().minus(2, ChronoUnit.HOURS));
        jdbc.update("UPDATE alerta_fraude SET rifa_id = ? WHERE id = ?", rifa.id(), alerta1.getId());

        AlertaFraude alerta2 = guardarAlerta(ReglaFraude.CANCELACIONES_REITERADAS, organizador,
                torneoRepository.getReferenceById(torneoId), null, "Cancelaciones reiteradas",
                Map.of("torneosCancelados", 3), EstadoAlertaFraude.REVISADA, Instant.now().minus(1, ChronoUnit.HOURS));
        entityManager.flush();
        entityManager.clear();

        // 401 sin sesión y 403 sin ALERTA_FRAUDE_REVISAR
        mockMvc.perform(get("/api/v1/admin/alertas-fraude"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/alertas-fraude")
                        .header("Authorization", "Bearer " + tokenParticipante))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));

        // Listado completo: PENDIENTE primero
        mockMvc.perform(get("/api/v1/admin/alertas-fraude")
                        .header("Authorization", "Bearer " + tokenSoporte))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(alerta1.getId()))
                .andExpect(jsonPath("$.content[0].estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.content[0].rifa.id").value(rifa.id()))
                .andExpect(jsonPath("$.content[0].usuario.username").value("part_fraude"))
                .andExpect(jsonPath("$.content[1].id").value(alerta2.getId()))
                .andExpect(jsonPath("$.content[1].estado").value("REVISADA"));

        // Filtros combinados
        mockMvc.perform(get("/api/v1/admin/alertas-fraude")
                        .param("estado", "PENDIENTE")
                        .param("regla", "COMPRA_MASIVA_RIFA")
                        .param("rifaId", String.valueOf(rifa.id()))
                        .param("usuarioId", String.valueOf(participante.getId()))
                        .header("Authorization", "Bearer " + tokenSoporte))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(alerta1.getId()));

        // Detalle y 404
        mockMvc.perform(get("/api/v1/admin/alertas-fraude/" + alerta1.getId())
                        .header("Authorization", "Bearer " + tokenSoporte))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datos.numerosComprados").value(15))
                .andExpect(jsonPath("$.torneo.id").value(torneoId));
        mockMvc.perform(get("/api/v1/admin/alertas-fraude/999999")
                        .header("Authorization", "Bearer " + tokenSoporte))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("ALERTA_FRAUDE_NO_ENCONTRADA"));

        // Revisar sin nota -> 400 VALIDACION
        mockMvc.perform(post("/api/v1/admin/alertas-fraude/" + alerta1.getId() + "/revisar")
                        .header("Authorization", "Bearer " + tokenSoporte)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nota\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"));

        // Revisar con nota válida -> 200 REVISADA, sale de pendientes, registra auditoría
        mockMvc.perform(post("/api/v1/admin/alertas-fraude/" + alerta1.getId() + "/revisar")
                        .header("Authorization", "Bearer " + tokenSoporte)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nota\":\"  Se revisó el historial del usuario  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("REVISADA"))
                .andExpect(jsonPath("$.nota").value("Se revisó el historial del usuario"))
                .andExpect(jsonPath("$.revisadaPor").value("soporte_fraude"))
                .andExpect(jsonPath("$.revisadaEn").isNotEmpty());

        mockMvc.perform(get("/api/v1/admin/alertas-fraude")
                        .param("estado", "PENDIENTE")
                        .header("Authorization", "Bearer " + tokenSoporte))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));

        // Revisar por segunda vez -> 409 ALERTA_FRAUDE_YA_REVISADA
        mockMvc.perform(post("/api/v1/admin/alertas-fraude/" + alerta1.getId() + "/revisar")
                        .header("Authorization", "Bearer " + tokenSoporte)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nota\":\"Reintento\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("ALERTA_FRAUDE_YA_REVISADA"));

        List<RegistroAuditoria> auditorias = libroAuditoriaService.listar(
                "ALERTA_FRAUDE", String.valueOf(alerta1.getId()), null, null, null, null, 0, 10).getContent();
        assertThat(auditorias).singleElement().satisfies(reg -> {
            assertThat(reg.usuarioId()).isEqualTo(soporte.getId());
            assertThat(reg.descripcion()).isEqualTo("COMPRA_MASIVA_RIFA – @part_fraude");
            assertThat(reg.detalle()).containsEntry("nota", "Se revisó el historial del usuario");
        });
    }

    // --- helpers -----------------------------------------------------------------------------------

    private Usuario usuarioConDni(String username, String dni) {
        Persona persona = new Persona();
        persona.setNombres(username);
        persona.setApellidos("Test");
        persona.setTipoDoc("DNI");
        persona.setNroDoc(dni);
        persona.setEstado(EstadoGeneral.ACTIVO);
        persona = personaRepository.saveAndFlush(persona);

        Usuario u = new Usuario();
        u.setUsername(username);
        u.setEmail(username + "@example.com");
        u.setEstado(EstadoUsuario.ACTIVO);
        u.setEmailVerificado(true);
        u.setPersona(persona);
        return usuarioRepository.saveAndFlush(u);
    }

    private Long crearTorneoAbierto(Long orgId, String nombre) {
        Long id = torneoService.crear(orgId, infoTorneo(nombre)).id();
        jdbc.update("""
                UPDATE torneo SET valor_inscripcion = 0, pct_pozo_competidores = 90, pct_premio_rifa = 0,
                                  estado = 'INSCRIPCION_ABIERTA', publicado_en = now()
                WHERE id = ?""", id);
        entityManager.clear();
        return id;
    }

    private long crearPartidoYObjetar(Long arbitroId, Long localId, Long visitanteId, String nombreTorneo) {
        Long torneoId = torneoService.crear(organizador.getId(), infoTorneo(nombreTorneo)).id();
        arbitrosTorneoService.designar(organizador.getId(), torneoId,
                new DesignarArbitroRequest(arbitroId, Set.of(PermisoArbitro.CARGAR_RESULTADOS)));
        jdbc.update("UPDATE torneo_arbitro SET estado = 'ACEPTADO' WHERE torneo_id = ?", torneoId);
        jdbc.update("""
                UPDATE torneo SET valor_inscripcion = 1000, pct_pozo_competidores = 90, pct_premio_rifa = 0,
                                  estado = 'INSCRIPCION_CERRADA', publicado_en = now()
                WHERE id = ?""", torneoId);
        entityManager.clear();

        inscribir(torneoId, localId);
        inscribir(torneoId, visitanteId);
        bracketService.generar(organizador.getId(), torneoId);
        entityManager.flush();
        entityManager.clear();

        Long partidoId = escenario.partido(torneoId, 1, 1);
        Long jugadorLocal = escenario.jugador(partidoId, "LOCAL");
        Long jugadorVisitante = escenario.jugador(partidoId, "VISITANTE");
        Long quienCarga = jugadorVisitante.equals(visitanteId) ? jugadorLocal : jugadorVisitante;
        resultadoService.cargar(quienCarga, partidoId, new ResultadoRequest(2, 0, null, null));
        evidenciaService.subir(visitanteId, partidoId, JPG, "http://localhost/api/v1/partidos/" + partidoId + "/evidencias");
        resultadoService.objetar(visitanteId, partidoId, new ObjecionRequest("Discrepancia", 1, 2, null, null));
        entityManager.flush();
        return partidoId;
    }

    private void inscribir(Long torneoId, Long usuarioId) {
        Inscripcion inscripcion = new Inscripcion();
        inscripcion.setTorneo(torneoRepository.findById(torneoId).orElseThrow());
        inscripcion.setUsuario(usuarioRepository.findById(usuarioId).orElseThrow());
        inscripcion.setEstado(EstadoInscripcion.CONFIRMADA);
        inscripcion.setReglamentoAceptadoEn(Instant.now());
        inscripcionRepository.saveAndFlush(inscripcion);
    }

    private AlertaFraude guardarAlerta(
            ReglaFraude regla,
            Usuario usuario,
            com.hydra.pica.plataforma_pica.torneo.domain.Torneo torneo,
            com.hydra.pica.plataforma_pica.rifa.domain.Rifa rifa,
            String motivo,
            Map<String, Object> datos,
            EstadoAlertaFraude estado,
            Instant creadaEn) {
        AlertaFraude alerta = new AlertaFraude();
        alerta.setRegla(regla);
        alerta.setUsuario(usuario);
        alerta.setTorneo(torneo);
        alerta.setRifa(rifa);
        alerta.setMotivo(motivo);
        alerta.setDatos(datos);
        alerta.setEstado(estado);
        alerta.setCreadaEn(creadaEn);
        if (estado == EstadoAlertaFraude.REVISADA) {
            alerta.setRevisadaPor(soporte);
            alerta.setRevisadaEn(creadaEn.plus(10, ChronoUnit.MINUTES));
            alerta.setNota("Revisada previamente");
        }
        return alertaFraudeRepository.saveAndFlush(alerta);
    }

    private String token(Usuario usuario, String rolNombre) {
        List<String> permisos = List.copyOf(permisoRepository.findCodigosByUsuarioId(usuario.getId()));
        return jwtService.generarAccessToken(
                usuario.getId(), usuario.getUsername(), List.of(rolNombre), permisos, usuario.getVersionSesion());
    }

    private static InformacionTorneoRequest infoTorneo(String nombre) {
        return new InformacionTorneoRequest(nombre, "Ajedrez", null, null,
                Modalidad.ONLINE, TipoParticipante.INDIVIDUAL, null, null, EN_DOS_SEMANAS, null, EN_UNA_SEMANA,
                "Partidas de 10 minutos", 16, 2, null, null);
    }

    private static byte[] jpgDePrueba() {
        byte[] bytes = new byte[2048];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        bytes[3] = (byte) 0xE0;
        return bytes;
    }
}
