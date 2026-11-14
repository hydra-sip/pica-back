package com.hydra.pica.plataforma_pica.rifa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.audit.AuditConstants;
import com.hydra.pica.plataforma_pica.common.security.JwtService;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoReclamo;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.ReclamoRifa;
import com.hydra.pica.plataforma_pica.rifa.dto.PremioRifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaRequest;
import com.hydra.pica.plataforma_pica.rifa.repository.ReclamoRifaRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.domain.UsuarioRol;
import com.hydra.pica.plataforma_pica.user.repository.PermisoRepository;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.RolRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRolRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
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
 * Integración end-to-end para la liberación de fondos congelados de un reclamo de rifa (HU-707, PICA-22, PICA-341).
 */
@SpringBootTest(properties = "app.cobro.modo=saldo")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class LiberacionReclamoIntegracionTest {

    private static final Instant MANIANA = Instant.now().plus(1, ChronoUnit.DAYS);
    private static final Instant EN_UNA_SEMANA = Instant.now().plus(7, ChronoUnit.DAYS);

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private RifaService rifaService;
    @Autowired private VentaRifaService ventaRifaService;
    @Autowired private ReclamoRifaRepository reclamoRifaRepository;
    @Autowired private RifaRepository rifaRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRolRepository usuarioRolRepository;
    @Autowired private PermisoRepository permisoRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private Usuario soporte;
    private Usuario organizador;
    private Usuario ganador;
    private Usuario reclamante;
    private String tokenSoporte;

    @BeforeEach
    void setUp() {
        soporte = crearUsuarioConRol("soporte_lib", "SOPORTE");
        organizador = crearUsuarioConRol("org_lib", "ORGANIZADOR");
        ganador = crearUsuarioConRol("ganador_lib", "PARTICIPANTE");
        reclamante = crearUsuarioConRol("reclamante_lib", "PARTICIPANTE");

        saldo(ganador.getId(), "10000.00");
        saldo(reclamante.getId(), "10000.00");
        tokenSoporte = tokenConPermisosReales(soporte, "SOPORTE");
    }

    @Test
    @DisplayName("SIN_FRAUDE libera al ganador original una única vez, registra auditoría y rechaza reintentos con 409")
    void liberarSinFraudeAcreditaAlGanadorYRechazaReintento() throws Exception {
        long rifaId = crearRifaSorteada(50); // $500 + 10% por número -> ganador #1 paga $550, reclamante #2 paga $550
        ReclamoRifa reclamo = crearReclamo(rifaId, reclamante, EstadoReclamo.RECHAZADO, "Semilla verificada");
        BigDecimal plataformaAntes = deLaPlataforma();
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(post("/api/v1/admin/reclamos/" + reclamo.getId() + "/liberar")
                        .header("Authorization", "Bearer " + tokenSoporte))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RECHAZADO"))
                .andExpect(jsonPath("$.rifa.estado").value("LIQUIDADA"))
                .andExpect(jsonPath("$.fondosLiberadosPor").value("soporte_lib"))
                .andExpect(jsonPath("$.fondosLiberadosMonto").value(1100.00))
                .andExpect(jsonPath("$.fondosLiberadosEn").isNotEmpty());

        entityManager.flush();
        entityManager.clear();

        // Ganador recibe el pozo ($1000.00 = 100% de $1000 vendidos) y libera su saldo retenido ($550)
        assertBilletera(ganador.getId(), "10450.00", "0.00", "1000.00");
        assertBilletera(reclamante.getId(), "9450.00", "0.00", "0.00");
        assertThat(deLaPlataforma().subtract(plataformaAntes)).isEqualByComparingTo("100.00");
        assertThat(jdbc.queryForObject("SELECT estado FROM custodia WHERE rifa_id = ?", String.class, rifaId))
                .isEqualTo("LIBERADA");

        // Auditoría completa con usuario soporte, rifa, monto y destino
        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT usuario_id, accion, entidad, entidad_id,
                       detalle->>'rifaId' AS rifa_id,
                       detalle->>'destino' AS destino,
                       detalle->>'ejecutadoPor' AS ejecutado_por,
                       (detalle->>'monto')::numeric AS monto
                FROM auditoria
                WHERE accion = 'LIQUIDACION' AND entidad = 'RECLAMO_RIFA' AND entidad_id = ?
                """, String.valueOf(reclamo.getId()));
        assertThat((Long) audit.get("usuario_id")).isEqualTo(soporte.getId());
        assertThat(audit.get("rifa_id")).isEqualTo(String.valueOf(rifaId));
        assertThat(audit.get("destino")).isEqualTo("LIQUIDACION_GANADORES");
        assertThat(audit.get("ejecutado_por")).isEqualTo("soporte_lib");
        assertThat((BigDecimal) audit.get("monto")).isEqualByComparingTo("1100.00");

        // Reintento devuelve 409 CUSTODIA_LIBERADA y no acredita por segunda vez
        mockMvc.perform(post("/api/v1/admin/reclamos/" + reclamo.getId() + "/liberar")
                        .header("Authorization", "Bearer " + tokenSoporte))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CUSTODIA_LIBERADA"));
        assertBilletera(ganador.getId(), "10450.00", "0.00", "1000.00");
    }

    @Test
    @DisplayName("FRAUDE_CONFIRMADO cancela rifa, marca números REINTEGRADO, devuelve 100% como REINTEGRO y rechaza reintentos")
    void liberarConFraudeReintegraCompradoresUnaVezYAudita() throws Exception {
        long rifaId = crearRifaSorteada(2); // Incluso dentro de las 48h del sorteo se puede reintegrar tras fraude
        ReclamoRifa reclamo = crearReclamo(rifaId, reclamante, EstadoReclamo.ACEPTADO, "Fraude comprobado");
        BigDecimal plataformaAntes = deLaPlataforma();
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(post("/api/v1/admin/reclamos/" + reclamo.getId() + "/liberar")
                        .header("Authorization", "Bearer " + tokenSoporte))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ACEPTADO"))
                .andExpect(jsonPath("$.rifa.estado").value("CANCELADA"))
                .andExpect(jsonPath("$.fondosLiberadosPor").value("soporte_lib"))
                .andExpect(jsonPath("$.fondosLiberadosMonto").value(1100.00))
                .andExpect(jsonPath("$.fondosLiberadosEn").isNotEmpty());

        entityManager.flush();
        entityManager.clear();

        // Ambos compradores recuperan el 100% pagado ($500 número + $50 comisión = $550) en saldoDisponible, sin ganancia
        assertBilletera(reclamante.getId(), "10000.00", "0.00", "0.00");
        assertBilletera(ganador.getId(), "10000.00", "0.00", "0.00");
        assertThat(deLaPlataforma().subtract(plataformaAntes)).isEqualByComparingTo("0.00");

        assertThat(rifaRepository.findById(rifaId).orElseThrow().getEstado()).isEqualTo(EstadoRifa.CANCELADA);
        assertThat(jdbc.queryForObject("SELECT estado FROM custodia WHERE rifa_id = ?", String.class, rifaId))
                .isEqualTo("REINTEGRADA");
        assertThat(jdbc.queryForList("SELECT estado FROM numero WHERE rifa_id = ?", String.class, rifaId))
                .containsExactlyInAnyOrder("REINTEGRADO", "REINTEGRADO");

        // Movimientos REINTEGRO CONFIRMADO con referencia a la rifa
        assertThat(jdbc.queryForList("""
                SELECT tipo || ':' || estado || ':' || monto FROM movimiento
                WHERE rifa_id = ? AND tipo = 'REINTEGRO' ORDER BY monto
                """, String.class, rifaId))
                .containsExactly("REINTEGRO:CONFIRMADO:550.00", "REINTEGRO:CONFIRMADO:550.00");

        // Auditoría de soporte registrada con rifa, monto, destino y usuario
        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT usuario_id, accion, entidad, entidad_id,
                       detalle->>'rifaId' AS rifa_id,
                       detalle->>'destino' AS destino,
                       detalle->>'ejecutadoPor' AS ejecutado_por,
                       (detalle->>'monto')::numeric AS monto
                FROM auditoria
                WHERE accion = 'REINTEGRO' AND entidad = 'RECLAMO_RIFA' AND entidad_id = ?
                """, String.valueOf(reclamo.getId()));
        assertThat((Long) audit.get("usuario_id")).isEqualTo(soporte.getId());
        assertThat(audit.get("rifa_id")).isEqualTo(String.valueOf(rifaId));
        assertThat(audit.get("destino")).isEqualTo("REINTEGRO_COMPRADORES");
        assertThat(audit.get("ejecutado_por")).isEqualTo("soporte_lib");
        assertThat((BigDecimal) audit.get("monto")).isEqualByComparingTo("1100.00");

        // Reintento devuelve 409 CUSTODIA_LIBERADA y no acredita de nuevo
        mockMvc.perform(post("/api/v1/admin/reclamos/" + reclamo.getId() + "/liberar")
                        .header("Authorization", "Bearer " + tokenSoporte))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CUSTODIA_LIBERADA"));
        assertBilletera(reclamante.getId(), "10000.00", "0.00", "0.00");
    }

    @Test
    @DisplayName("Intentar liberar un reclamo PENDIENTE devuelve 409 RECLAMO_NO_RESUELTO; con plazo abierto sin fraude, 409 FONDOS_EN_CUSTODIA")
    void liberarReclamoNoResueltoYPlazoAbiertoDevuelven409() throws Exception {
        long rifaId = crearRifaSorteada(2); // 2h desde sorteo -> aún dentro del plazo de 48h
        ReclamoRifa pendiente = crearReclamo(rifaId, reclamante, EstadoReclamo.PENDIENTE, null);
        ReclamoRifa rechazado = crearReclamo(rifaId, ganador, EstadoReclamo.RECHAZADO, "Sin fraude");
        jdbc.update("UPDATE rifa SET estado = 'EN_REVISION' WHERE id = ?", rifaId);
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(post("/api/v1/admin/reclamos/" + pendiente.getId() + "/liberar")
                        .header("Authorization", "Bearer " + tokenSoporte))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("RECLAMO_NO_RESUELTO"));

        mockMvc.perform(post("/api/v1/admin/reclamos/" + rechazado.getId() + "/liberar")
                        .header("Authorization", "Bearer " + tokenSoporte))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("FONDOS_EN_CUSTODIA"));
    }

    // --- helpers -----------------------------------------------------------------------------------

    private Usuario crearUsuarioConRol(String prefijo, String rolNombre) {
        Persona persona = new Persona();
        persona.setNombres(prefijo);
        persona.setApellidos("Test");
        persona.setEstado(EstadoGeneral.ACTIVO);
        persona = personaRepository.saveAndFlush(persona);

        Usuario usuario = new Usuario();
        usuario.setUsername(prefijo);
        usuario.setEmail(prefijo + "@example.com");
        usuario.setEstado(EstadoUsuario.ACTIVO);
        usuario.setEmailVerificado(true);
        usuario.setPersona(persona);
        usuario = usuarioRepository.saveAndFlush(usuario);

        UsuarioRol asignacion = new UsuarioRol(usuario, rolRepository.findByNombre(rolNombre).orElseThrow());
        asignacion.setAsignadoEn(Instant.now());
        asignacion.setAsignadoPor(AuditConstants.SISTEMA);
        usuarioRolRepository.saveAndFlush(asignacion);
        return usuario;
    }

    private String tokenConPermisosReales(Usuario usuario, String rolNombre) {
        List<String> permisos = List.copyOf(permisoRepository.findCodigosByUsuarioId(usuario.getId()));
        return jwtService.generarAccessToken(
                usuario.getId(), usuario.getUsername(), List.of(rolNombre), permisos, usuario.getVersionSesion());
    }

    private void saldo(Long usuarioId, String disponible) {
        jdbc.update("""
                INSERT INTO billetera (usuario_id, saldo_disponible) VALUES (?, ?)
                ON CONFLICT (usuario_id) DO UPDATE SET saldo_disponible = excluded.saldo_disponible
                """, usuarioId, new BigDecimal(disponible));
    }

    private BigDecimal deLaPlataforma() {
        entityManager.flush();
        return jdbc.queryForObject("SELECT saldo_disponible FROM billetera WHERE usuario_id IS NULL", BigDecimal.class);
    }

    private void assertBilletera(Long usuarioId, String disponible, String retenido, String ganancia) {
        Map<String, Object> billetera = jdbc.queryForMap(
                "SELECT saldo_disponible, saldo_retenido, ganancia_acumulada FROM billetera WHERE usuario_id = ?",
                usuarioId);
        assertThat((BigDecimal) billetera.get("saldo_disponible")).isEqualByComparingTo(disponible);
        assertThat((BigDecimal) billetera.get("saldo_retenido")).isEqualByComparingTo(retenido);
        assertThat((BigDecimal) billetera.get("ganancia_acumulada")).isEqualByComparingTo(ganancia);
    }

    private long crearRifaSorteada(int horasDesdeSorteo) {
        RifaDetalle creada = rifaService.crear(organizador.getId(), new RifaRequest(
                "Rifa liberación " + System.nanoTime(), null, 1, 50,
                new BigDecimal("500"), MANIANA, EN_UNA_SEMANA, EN_UNA_SEMANA.plus(1, ChronoUnit.DAYS), null, null));
        rifaService.reemplazarPremios(organizador.getId(), creada.id(),
                List.of(new PremioRifaRequest(BigDecimal.valueOf(100), null)));
        rifaService.publicar(organizador.getId(), creada.id());
        jdbc.update("UPDATE rifa SET venta_desde = now() - interval '1 hour' WHERE id = ?", creada.id());
        entityManager.clear();

        ventaRifaService.reservar(ganador.getId(), creada.id(), List.of(1));
        ventaRifaService.comprar(ganador.getId(), creada.id(), List.of(1), "gan-" + creada.id());
        ventaRifaService.reservar(reclamante.getId(), creada.id(), List.of(2));
        ventaRifaService.comprar(reclamante.getId(), creada.id(), List.of(2), "rec-" + creada.id());

        jdbc.update("UPDATE premio_rifa SET numero_ganador = 1 WHERE rifa_id = ? AND puesto = 1", creada.id());
        jdbc.update("""
                UPDATE rifa SET estado = 'SORTEADA',
                                venta_desde = now() - interval '10 days',
                                venta_hasta = now() - interval '5 days',
                                sorteada_en = now() - (? || ' hours')::interval,
                                semilla_obtenida_en = now() - (? || ' hours')::interval
                WHERE id = ?
                """, String.valueOf(horasDesdeSorteo), String.valueOf(horasDesdeSorteo), creada.id());
        entityManager.flush();
        entityManager.clear();
        return creada.id();
    }

    private ReclamoRifa crearReclamo(long rifaId, Usuario autor, EstadoReclamo estado, String resolucion) {
        ReclamoRifa reclamo = new ReclamoRifa();
        reclamo.setRifa(rifaRepository.getReferenceById(rifaId));
        reclamo.setUsuario(autor);
        reclamo.setMotivo("Revisión del sorteo");
        reclamo.setEstado(estado);
        if (estado != EstadoReclamo.PENDIENTE) {
            reclamo.setResolucion(resolucion);
            reclamo.setResueltoEn(Instant.now().minus(1, ChronoUnit.HOURS));
            reclamo.setResueltoPor(soporte.getUsername());
        }
        return reclamoRifaRepository.saveAndFlush(reclamo);
    }
}

