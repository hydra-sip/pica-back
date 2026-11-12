package com.hydra.pica.plataforma_pica.rifa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.billetera.service.LiquidacionRifaService;
import com.hydra.pica.plataforma_pica.common.audit.AuditConstants;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.security.JwtService;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoReclamo;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.ReclamoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.dto.DecisionReclamo;
import com.hydra.pica.plataforma_pica.rifa.dto.PremioRifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaRequest;
import com.hydra.pica.plataforma_pica.rifa.event.ReclamoRifaResuelto;
import com.hydra.pica.plataforma_pica.rifa.repository.ReclamoRifaRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Modulo;
import com.hydra.pica.plataforma_pica.user.domain.Permiso;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integración completa de la gestión administrativa de reclamos (HU-706, PICA-339) contra PostgreSQL real.
 */
@SpringBootTest(properties = "app.cobro.modo=saldo")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
@Transactional
class ReclamoAdminIntegracionTest {

    private static final Instant MANIANA = Instant.now().plus(1, ChronoUnit.DAYS);
    private static final Instant EN_UNA_SEMANA = Instant.now().plus(7, ChronoUnit.DAYS);

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private PermisoRepository permisoRepository;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private UsuarioRolRepository usuarioRolRepository;
    @Autowired private RifaService rifaService;
    @Autowired private VentaRifaService ventaRifaService;
    @Autowired private RifaRepository rifaRepository;
    @Autowired private ReclamoRifaRepository reclamoRifaRepository;
    @Autowired private LiquidacionRifaService liquidacionRifaService;
    @Autowired private ApplicationEvents applicationEvents;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private Usuario soporte;
    private Usuario participante;
    private Usuario organizador;
    private Usuario comprador1;
    private Usuario comprador2;
    private long rifaId;
    private String tokenSoporte;
    private String tokenParticipante;

    @BeforeEach
    void setUp() {
        soporte = crearUsuarioConRol("soporte_reclamo", "SOPORTE");
        participante = crearUsuarioConRol("part_reclamo", "PARTICIPANTE");
        organizador = crearUsuarioConRol("org_reclamo", "ORGANIZADOR");
        comprador1 = crearUsuarioConRol("comp1_reclamo", "PARTICIPANTE");
        comprador2 = crearUsuarioConRol("comp2_reclamo", "PARTICIPANTE");

        saldo(comprador1.getId(), "10000.00");
        saldo(comprador2.getId(), "10000.00");

        rifaId = crearRifaSorteadaHace50Horas();
        tokenSoporte = tokenConPermisosReales(soporte, "SOPORTE");
        tokenParticipante = tokenConPermisosReales(participante, "PARTICIPANTE");
    }

    @Test
    @DisplayName("La migración V45 crea RECLAMO_RESOLVER en RIFAS y lo asigna a SOPORTE, ADMINISTRADOR y SUPER_USUARIO")
    void permisoSeedAsignadoARoles() {
        Permiso permiso = permisoRepository.findByCodigo("RECLAMO_RESOLVER").orElseThrow();
        assertThat(permiso.getModulo()).isEqualTo(Modulo.RIFAS);

        for (String rolNombre : List.of("SOPORTE", "ADMINISTRADOR", "SUPER_USUARIO")) {
            assertThat(rolRepository.findByNombre(rolNombre).orElseThrow().getPermisos())
                    .extracting(Permiso::getCodigo)
                    .contains("RECLAMO_RESOLVER");
        }
        assertThat(permisoRepository.findCodigosByUsuarioId(soporte.getId()))
                .containsExactlyInAnyOrder("CANCELACION_REVISAR", "RECLAMO_RESOLVER");
    }

    @Test
    @DisplayName("GET /api/v1/admin/reclamos controla auth/permiso, filtra por estado y ordena pendientes primero")
    void listarAdminControlaAccesoOrdenaYFiltra() throws Exception {
        ReclamoRifa resuelto = crearReclamo(comprador1, "Reclamo antiguo", EstadoReclamo.RECHAZADO,
                "Ya resuelto", Instant.now().minus(10, ChronoUnit.HOURS), "soporte_previo",
                Instant.now().minus(12, ChronoUnit.HOURS));
        ReclamoRifa pendienteViejo = crearReclamo(comprador1, "Pendiente viejo", EstadoReclamo.PENDIENTE,
                null, null, null, Instant.now().minus(5, ChronoUnit.HOURS));
        ReclamoRifa pendienteNuevo = crearReclamo(comprador2, "Pendiente nuevo", EstadoReclamo.PENDIENTE,
                null, null, null, Instant.now().minus(1, ChronoUnit.HOURS));
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/v1/admin/reclamos"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/admin/reclamos")
                        .header("Authorization", "Bearer " + tokenParticipante))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));

        mockMvc.perform(get("/api/v1/admin/reclamos")
                        .header("Authorization", "Bearer " + tokenSoporte))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].id").value(pendienteNuevo.getId()))
                .andExpect(jsonPath("$.content[0].estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.content[0].rifa.id").value(rifaId))
                .andExpect(jsonPath("$.content[0].reclamante.username").value("comp2_reclamo"))
                .andExpect(jsonPath("$.content[1].id").value(pendienteViejo.getId()))
                .andExpect(jsonPath("$.content[2].id").value(resuelto.getId()))
                .andExpect(jsonPath("$.content[2].estado").value("RECHAZADO"));

        mockMvc.perform(get("/api/v1/admin/reclamos")
                        .param("estado", "RECHAZADO")
                        .header("Authorization", "Bearer " + tokenSoporte))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(resuelto.getId()));

        mockMvc.perform(get("/api/v1/admin/reclamos/" + pendienteNuevo.getId())
                        .header("Authorization", "Bearer " + tokenSoporte))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.motivo").value("Pendiente nuevo"));
    }

    @Test
    @DisplayName("Resolver reclamos valida 400/404/409, audita, emite evento y saca la rifa de EN_REVISION al cerrar el último")
    void resolucionCompletaYTransicionDeRifa() throws Exception {
        ReclamoRifa reclamo1 = crearReclamo(comprador1, "Motivo 1", EstadoReclamo.PENDIENTE,
                null, null, null, Instant.now().minus(2, ChronoUnit.HOURS));
        ReclamoRifa reclamo2 = crearReclamo(comprador2, "Motivo 2", EstadoReclamo.PENDIENTE,
                null, null, null, Instant.now().minus(1, ChronoUnit.HOURS));
        jdbc.update("UPDATE rifa SET estado = 'EN_REVISION' WHERE id = ?", rifaId);
        entityManager.flush();
        entityManager.clear();

        // 400 sin resolución escrita
        mockMvc.perform(post("/api/v1/admin/reclamos/" + reclamo1.getId() + "/resolucion")
                        .header("Authorization", "Bearer " + tokenSoporte)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"SIN_FRAUDE\",\"resolucion\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"));

        // 404 reclamo inexistente
        mockMvc.perform(post("/api/v1/admin/reclamos/999999/resolucion")
                        .header("Authorization", "Bearer " + tokenSoporte)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"SIN_FRAUDE\",\"resolucion\":\"No existe\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("RECLAMO_NO_ENCONTRADO"));

        // Resolver reclamo 1: aún queda reclamo 2 pendiente -> rifa sigue EN_REVISION
        mockMvc.perform(post("/api/v1/admin/reclamos/" + reclamo1.getId() + "/resolucion")
                        .header("Authorization", "Bearer " + tokenSoporte)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"SIN_FRAUDE\",\"resolucion\":\"Semilla válida para el caso 1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RECHAZADO"))
                .andExpect(jsonPath("$.resolucion").value("Semilla válida para el caso 1"))
                .andExpect(jsonPath("$.resueltoPor").value("soporte_reclamo"))
                .andExpect(jsonPath("$.resueltoEn").isNotEmpty())
                .andExpect(jsonPath("$.rifa.estado").value("EN_REVISION"));

        // 409 al intentar reabrir/resolver de nuevo el reclamo 1
        mockMvc.perform(post("/api/v1/admin/reclamos/" + reclamo1.getId() + "/resolucion")
                        .header("Authorization", "Bearer " + tokenSoporte)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"FRAUDE_CONFIRMADO\",\"resolucion\":\"Apelación\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("RECLAMO_YA_RESUELTO"));

        // Resolver reclamo 2: ya no quedan pendientes -> rifa vuelve a SORTEADA
        mockMvc.perform(post("/api/v1/admin/reclamos/" + reclamo2.getId() + "/resolucion")
                        .header("Authorization", "Bearer " + tokenSoporte)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"SIN_FRAUDE\",\"resolucion\":\"Semilla válida para el caso 2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RECHAZADO"))
                .andExpect(jsonPath("$.rifa.estado").value("SORTEADA"));

        entityManager.flush();
        entityManager.clear();

        Rifa rifaActualizada = rifaRepository.findById(rifaId).orElseThrow();
        assertThat(rifaActualizada.getEstado()).isEqualTo(EstadoRifa.SORTEADA);

        // Auditoría registrada para ambos reclamos
        List<Map<String, Object>> auditorias = jdbc.queryForList("""
                SELECT usuario_id, accion, entidad_id, detalle->>'decision' AS decision,
                       detalle->>'rifaEstadoFinal' AS rifa_estado
                FROM auditoria
                WHERE entidad = 'RECLAMO_RIFA' AND entidad_id IN (?, ?)
                ORDER BY id ASC
                """, String.valueOf(reclamo1.getId()), String.valueOf(reclamo2.getId()));
        assertThat(auditorias).hasSize(2);
        assertThat(auditorias.get(0)).containsEntry("usuario_id", soporte.getId())
                .containsEntry("accion", "LAUDO")
                .containsEntry("decision", "SIN_FRAUDE")
                .containsEntry("rifa_estado", "EN_REVISION");
        assertThat(auditorias.get(1)).containsEntry("rifa_estado", "SORTEADA");

        // Eventos emitidos para notificación por mail
        List<ReclamoRifaResuelto> eventos = applicationEvents.stream(ReclamoRifaResuelto.class).toList();
        assertThat(eventos).hasSize(2);
        assertThat(eventos.get(0).rifaSalioDeRevision()).isFalse();
        assertThat(eventos.get(1).rifaSalioDeRevision()).isTrue();
        assertThat(eventos.get(1).decision()).isEqualTo(DecisionReclamo.SIN_FRAUDE);
    }

    @Test
    @DisplayName("Sin fraude (todos RECHAZADO) habilita la liquidación normal; con fraude confirmado (ACEPTADO) la bloquea")
    void liquidacionRespetaDecisionDeReclamos() throws Exception {
        ReclamoRifa reclamoSinFraude = crearReclamo(comprador1, "Duda 1", EstadoReclamo.PENDIENTE,
                null, null, null, Instant.now().minus(1, ChronoUnit.HOURS));
        jdbc.update("UPDATE rifa SET estado = 'EN_REVISION' WHERE id = ?", rifaId);
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(post("/api/v1/admin/reclamos/" + reclamoSinFraude.getId() + "/resolucion")
                        .header("Authorization", "Bearer " + tokenSoporte)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"SIN_FRAUDE\",\"resolucion\":\"Sorteo correcto\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rifa.estado").value("SORTEADA"));

        assertThat(rifaRepository.paraLiquidar(Instant.now().minus(48, ChronoUnit.HOURS))).contains(rifaId);
        liquidacionRifaService.liquidarRifa(rifaId);
        assertThat(rifaRepository.findById(rifaId).orElseThrow().getEstado()).isEqualTo(EstadoRifa.LIQUIDADA);

        // Segunda rifa con fraude confirmado: vuelve a SORTEADA pero queda frenada para liquidación normal (espera T707-1)
        long rifaFraude = crearRifaSorteadaHace50Horas();
        Rifa entidadFraude = rifaRepository.findById(rifaFraude).orElseThrow();
        entidadFraude.setEstado(EstadoRifa.EN_REVISION);
        rifaRepository.saveAndFlush(entidadFraude);

        ReclamoRifa reclamoFraude = new ReclamoRifa();
        reclamoFraude.setRifa(entidadFraude);
        reclamoFraude.setUsuario(comprador2);
        reclamoFraude.setMotivo("Fraude detectado");
        reclamoFraude.setEstado(EstadoReclamo.PENDIENTE);
        reclamoFraude.setCreadoEn(Instant.now().minus(1, ChronoUnit.HOURS));
        reclamoFraude = reclamoRifaRepository.saveAndFlush(reclamoFraude);
        entityManager.clear();

        mockMvc.perform(post("/api/v1/admin/reclamos/" + reclamoFraude.getId() + "/resolucion")
                        .header("Authorization", "Bearer " + tokenSoporte)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"FRAUDE_CONFIRMADO\",\"resolucion\":\"Lista manipulada\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ACEPTADO"))
                .andExpect(jsonPath("$.rifa.estado").value("SORTEADA"));

        assertThat(rifaRepository.paraLiquidar(Instant.now().minus(48, ChronoUnit.HOURS)))
                .doesNotContain(rifaFraude);
        assertThatThrownBy(() -> liquidacionRifaService.liquidarRifa(rifaFraude))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCodigo()).isEqualTo(CodigoError.FONDOS_EN_CUSTODIA);
                });
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

    private long crearRifaSorteadaHace50Horas() {
        RifaDetalle creada = rifaService.crear(organizador.getId(), new RifaRequest(
                "Rifa disputa " + System.nanoTime(), null, 1, 50,
                new BigDecimal("500"), MANIANA, EN_UNA_SEMANA, EN_UNA_SEMANA.plus(1, ChronoUnit.DAYS), null, null));
        rifaService.reemplazarPremios(organizador.getId(), creada.id(),
                List.of(new PremioRifaRequest(BigDecimal.valueOf(100), null)));
        rifaService.publicar(organizador.getId(), creada.id());
        jdbc.update("UPDATE rifa SET venta_desde = now() - interval '1 hour' WHERE id = ?", creada.id());
        entityManager.clear();

        ventaRifaService.reservar(comprador1.getId(), creada.id(), List.of(1));
        ventaRifaService.comprar(comprador1.getId(), creada.id(), List.of(1), "comp1-" + creada.id());
        ventaRifaService.reservar(comprador2.getId(), creada.id(), List.of(2));
        ventaRifaService.comprar(comprador2.getId(), creada.id(), List.of(2), "comp2-" + creada.id());

        jdbc.update("UPDATE premio_rifa SET numero_ganador = 1 WHERE rifa_id = ? AND puesto = 1", creada.id());
        jdbc.update("""
                UPDATE rifa SET estado = 'SORTEADA', venta_desde = now() - interval '10 days',
                                venta_hasta = now() - interval '5 days',
                                sorteada_en = now() - interval '50 hours',
                                semilla_obtenida_en = now() - interval '50 hours'
                WHERE id = ?
                """, creada.id());
        entityManager.flush();
        entityManager.clear();
        return creada.id();
    }

    private ReclamoRifa crearReclamo(Usuario autor, String motivo, EstadoReclamo estado,
                                     String resolucion, Instant resueltoEn, String resueltoPor, Instant creadoEn) {
        ReclamoRifa reclamo = new ReclamoRifa();
        reclamo.setRifa(rifaRepository.getReferenceById(rifaId));
        reclamo.setUsuario(autor);
        reclamo.setMotivo(motivo);
        reclamo.setEstado(estado);
        reclamo.setResolucion(resolucion);
        reclamo.setResueltoEn(resueltoEn);
        reclamo.setResueltoPor(resueltoPor);
        reclamo.setCreadoEn(creadoEn);
        return reclamoRifaRepository.saveAndFlush(reclamo);
    }
}

