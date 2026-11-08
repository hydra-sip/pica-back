package com.hydra.pica.plataforma_pica.billetera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.billetera.domain.Billetera;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoBilletera;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.repository.BilleteraRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.MovimientoRepository;
import com.hydra.pica.plataforma_pica.common.security.JwtService;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Ticket;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.TicketRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Formato;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.TipoDoc;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import com.hydra.pica.plataforma_pica.user.service.AuthService;
import com.hydra.pica.plataforma_pica.user.service.DatosPersona;
import com.hydra.pica.plataforma_pica.user.service.NuevoUsuario;
import com.hydra.pica.plataforma_pica.user.service.UsuarioService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pruebas de integración de la billetera virtual contra PostgreSQL (HU-602, T602-1).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class BilleteraIntegracionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UsuarioService usuarioService;
    @Autowired private AuthService authService;
    @Autowired private JwtService jwtService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private BilleteraRepository billeteraRepository;
    @Autowired private MovimientoRepository movimientoRepository;
    @Autowired private TorneoRepository torneoRepository;
    @Autowired private RifaRepository rifaRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("Alta de usuario por formulario asocia automáticamente billetera en cero")
    void altaUsuarioFormularioCreaBilleteraEnCero() {
        String doc = "35" + System.currentTimeMillis() % 1000000;
        String user = "userform" + UUID.randomUUID().toString().substring(0, 6);
        DatosPersona persona = new DatosPersona(TipoDoc.DNI, doc, "Marcos", "Paz", LocalDate.of(1995, 3, 10), null, null);

        Usuario creado = usuarioService.crear(NuevoUsuario.autoRegistro(user, user + "@example.com", "Pica2026", persona));
        entityManager.flush();
        entityManager.clear();

        Billetera billetera = billeteraRepository.findByUsuarioId(creado.getId()).orElseThrow();
        assertThat(billetera.getSaldoDisponible()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(billetera.getSaldoRetenido()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(billetera.getGananciaAcumulada()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(billetera.getMoneda()).isEqualTo("ARS");
        assertThat(billetera.getEstado()).isEqualTo(EstadoBilletera.ACTIVA);
    }

    @Test
    @DisplayName("Alta de usuario por Google Login asocia automáticamente billetera en cero")
    void altaUsuarioGoogleCreaBilleteraEnCero() {
        String googleSub = "sub-google-" + UUID.randomUUID();
        String email = "google." + UUID.randomUUID().toString().substring(0, 6) + "@example.com";

        Usuario creado = authService.procesarLoginGoogle(googleSub, email, true, "Julia", "Roca");
        entityManager.flush();
        entityManager.clear();

        Billetera billetera = billeteraRepository.findByUsuarioId(creado.getId()).orElseThrow();
        assertThat(billetera.getSaldoDisponible()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(billetera.getSaldoRetenido()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(billetera.getGananciaAcumulada()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(billetera.getMoneda()).isEqualTo("ARS");
        assertThat(billetera.getEstado()).isEqualTo(EstadoBilletera.ACTIVA);
    }

    @Test
    @DisplayName("Migración V37: los usuarios existentes que no tenían billetera la tienen tras la migración")
    void usuariosExistentesTienenBilleteraTrasMigracion() {
        // Verificar que ningún usuario en la base quedó sin billetera
        Integer usuariosSinBilletera = jdbc.queryForObject(
                "SELECT count(*) FROM usuario u WHERE NOT EXISTS (SELECT 1 FROM billetera b WHERE b.usuario_id = u.id)",
                Integer.class);
        assertThat(usuariosSinBilletera).isEqualTo(0);
    }

    @Test
    @DisplayName("GET /api/v1/billetera sin sesión responde 401 UNAUTHORIZED")
    void verBilleteraSinSesionResponde401() throws Exception {
        mockMvc.perform(get("/api/v1/billetera"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/billetera con sesión responde los datos y saldos propios del usuario")
    void verBilleteraConSesionDevuelveSaldos() throws Exception {
        Usuario usuario = crearUsuarioActivo("userbilletera");
        Billetera billetera = billeteraRepository.findByUsuarioId(usuario.getId()).orElseThrow();
        billetera.setSaldoDisponible(new BigDecimal("18500.00"));
        billetera.setSaldoRetenido(new BigDecimal("2500.00"));
        billetera.setGananciaAcumulada(new BigDecimal("5000.00"));
        billeteraRepository.saveAndFlush(billetera);
        entityManager.clear();

        String token = tokenDe(usuario);

        mockMvc.perform(get("/api/v1/billetera").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saldoDisponible").value(18500.00))
                .andExpect(jsonPath("$.saldoRetenido").value(2500.00))
                .andExpect(jsonPath("$.gananciaAcumulada").value(5000.00))
                .andExpect(jsonPath("$.moneda").value("ARS"))
                .andExpect(jsonPath("$.estado").value("ACTIVA"));
    }

    @Test
    @DisplayName("GET /api/v1/billetera/movimientos sin sesión responde 401 UNAUTHORIZED")
    void movimientosSinSesionResponde401() throws Exception {
        mockMvc.perform(get("/api/v1/billetera/movimientos"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/billetera/movimientos: orden más nuevo primero y aislamiento entre usuarios")
    void movimientosAislamientoYOrden() throws Exception {
        Usuario usuarioA = crearUsuarioActivo("usera");
        Usuario usuarioB = crearUsuarioActivo("userb");

        Billetera billeteraA = billeteraRepository.findByUsuarioId(usuarioA.getId()).orElseThrow();
        Billetera billeteraB = billeteraRepository.findByUsuarioId(usuarioB.getId()).orElseThrow();

        // Movimiento 1 de A
        Movimiento movA1 = crearMovimiento(billeteraA, TipoMovimiento.CARGA, new BigDecimal("5000.00"),
                new BigDecimal("5000.00"), "ref-a1", "Primer carga");

        // Movimiento de B (no debe aparecer para A)
        crearMovimiento(billeteraB, TipoMovimiento.CARGA, new BigDecimal("9999.00"),
                new BigDecimal("9999.00"), "ref-b1", "Carga de otro usuario");

        // Movimiento 2 de A
        Movimiento movA2 = crearMovimiento(billeteraA, TipoMovimiento.CARGA, new BigDecimal("3000.00"),
                new BigDecimal("8000.00"), "ref-a2", "Segunda carga");

        entityManager.flush();
        entityManager.clear();

        String tokenA = tokenDe(usuarioA);

        mockMvc.perform(get("/api/v1/billetera/movimientos?page=0&size=10").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(movA2.getId()))
                .andExpect(jsonPath("$.content[0].referenciaExterna").value("ref-a2"))
                .andExpect(jsonPath("$.content[0].saldoResultante").value(8000.00))
                .andExpect(jsonPath("$.content[1].id").value(movA1.getId()))
                .andExpect(jsonPath("$.content[1].referenciaExterna").value("ref-a1"))
                .andExpect(jsonPath("$.content[1].saldoResultante").value(5000.00));

        // El parámetro sort del cliente se ignora: el orden se mantiene estricto por creadoEn DESC, id DESC (movA2 antes que movA1 aunque movA1 tenga mayor monto)
        mockMvc.perform(get("/api/v1/billetera/movimientos?sort=monto,desc").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(movA2.getId()))
                .andExpect(jsonPath("$.content[1].id").value(movA1.getId()));
    }

    @Test
    @DisplayName("GET /api/v1/billetera/movimientos: vínculo con torneo y con rifa")
    void movimientosConTorneoYRifa() throws Exception {
        Usuario usuario = crearUsuarioActivo("uservinculos");
        Billetera billetera = billeteraRepository.findByUsuarioId(usuario.getId()).orElseThrow();

        // Crear torneo
        Torneo torneo = new Torneo();
        torneo.setOrganizador(usuario);
        torneo.setNombre("Torneo Master PICA");
        torneo.setDisciplina("Fútbol 5");
        torneo.setFormato(Formato.ELIMINACION_DIRECTA);
        torneo.setEstado(EstadoTorneo.BORRADOR);
        torneo.setSlug("torneo-master-pica");
        torneo = torneoRepository.save(torneo);

        // Movimiento de torneo
        Movimiento movTorneo = new Movimiento();
        movTorneo.setBilletera(billetera);
        movTorneo.setTorneo(torneo);
        movTorneo.setTipo(TipoMovimiento.PREMIO);
        movTorneo.setMonto(new BigDecimal("2000.00"));
        movTorneo.setMoneda("ARS");
        movTorneo.setSaldoResultante(new BigDecimal("3000.00"));
        movTorneo.setEstado(EstadoMovimiento.CONFIRMADO);
        movTorneo.setDescripcion("Premio torneo");
        movTorneo = movimientoRepository.save(movTorneo);

        // Crear rifa y ticket vinculado a un movimiento
        Rifa rifa = new Rifa();
        rifa.setOrganizador(usuario);
        rifa.setTitulo("Gran Rifa Anual");
        rifa.setNumeroDesde(1);
        rifa.setNumeroHasta(100);
        rifa.setPrecioNumero(new BigDecimal("500.00"));
        rifa = rifaRepository.save(rifa);

        Movimiento movRifa = new Movimiento();
        movRifa.setBilletera(billetera);
        movRifa.setTipo(TipoMovimiento.COMPRA_NUMERO);
        movRifa.setMonto(new BigDecimal("1000.00"));
        movRifa.setMoneda("ARS");
        movRifa.setSaldoResultante(new BigDecimal("2000.00"));
        movRifa.setEstado(EstadoMovimiento.CONFIRMADO);
        movRifa.setDescripcion("Compra 2 números");
        movRifa = movimientoRepository.save(movRifa);

        Ticket ticket = new Ticket();
        ticket.setCodigo("TCK-" + System.currentTimeMillis() % 100000);
        ticket.setRifa(rifa);
        ticket.setComprador(usuario);
        ticket.setMovimiento(movRifa);
        ticket.setMonto(new BigDecimal("1000.00"));
        ticket.setComision(BigDecimal.ZERO);
        ticket.setClaveIdempotencia("idemp-" + UUID.randomUUID());
        ticketRepository.save(ticket);

        entityManager.flush();
        entityManager.clear();

        String token = tokenDe(usuario);

        mockMvc.perform(get("/api/v1/billetera/movimientos").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(movRifa.getId()))
                .andExpect(jsonPath("$.content[0].rifa.id").value(rifa.getId()))
                .andExpect(jsonPath("$.content[0].rifa.titulo").value("Gran Rifa Anual"))
                .andExpect(jsonPath("$.content[1].id").value(movTorneo.getId()))
                .andExpect(jsonPath("$.content[1].torneo.id").value(torneo.getId()))
                .andExpect(jsonPath("$.content[1].torneo.nombre").value("Torneo Master PICA"))
                .andExpect(jsonPath("$.content[1].torneo.slug").value("torneo-master-pica"));
    }

    private Usuario crearUsuarioActivo(String prefijo) {
        String username = prefijo + UUID.randomUUID().toString().substring(0, 5);
        Persona persona = new Persona();
        persona.setNombres("Nombre");
        persona.setApellidos("Apellido");
        persona.setEstado(EstadoGeneral.ACTIVO);
        persona = personaRepository.save(persona);

        Usuario usuario = new Usuario();
        usuario.setUsername(username);
        usuario.setEmail(username + "@example.com");
        usuario.setEstado(EstadoUsuario.ACTIVO);
        usuario.setEmailVerificado(true);
        usuario.setPersona(persona);
        usuario = usuarioRepository.save(usuario);

        Billetera billetera = new Billetera();
        billetera.setUsuario(usuario);
        billetera.setSaldoDisponible(BigDecimal.ZERO);
        billetera.setSaldoRetenido(BigDecimal.ZERO);
        billetera.setGananciaAcumulada(BigDecimal.ZERO);
        billetera.setMoneda("ARS");
        billetera.setEstado(EstadoBilletera.ACTIVA);
        billeteraRepository.save(billetera);

        return usuario;
    }

    private Movimiento crearMovimiento(Billetera billetera, TipoMovimiento tipo, BigDecimal monto,
                                       BigDecimal saldoResultante, String ref, String desc) {
        Movimiento m = new Movimiento();
        m.setBilletera(billetera);
        m.setTipo(tipo);
        m.setMonto(monto);
        m.setMoneda("ARS");
        m.setSaldoResultante(saldoResultante);
        m.setEstado(EstadoMovimiento.CONFIRMADO);
        m.setReferenciaExterna(ref);
        m.setDescripcion(desc);
        return movimientoRepository.save(m);
    }

    private String tokenDe(Usuario usuario) {
        return jwtService.generarAccessToken(
                usuario.getId(),
                usuario.getUsername(),
                List.of("PARTICIPANTE"),
                List.of(),
                usuario.getVersionSesion());
    }
}

