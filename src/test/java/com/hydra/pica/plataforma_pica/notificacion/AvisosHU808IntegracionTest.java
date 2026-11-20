package com.hydra.pica.plataforma_pica.notificacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hydra.pica.plataforma_pica.EscenarioTorneo;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.common.security.JwtService;
import com.hydra.pica.plataforma_pica.notificacion.aviso.AvisoCheckinAbierto;
import com.hydra.pica.plataforma_pica.partido.dto.ResultadoRequest;
import com.hydra.pica.plataforma_pica.partido.service.CheckinService;
import com.hydra.pica.plataforma_pica.partido.service.ProgramacionService;
import com.hydra.pica.plataforma_pica.partido.service.ResultadoService;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoReclamo;
import com.hydra.pica.plataforma_pica.rifa.domain.ReclamoRifa;
import com.hydra.pica.plataforma_pica.rifa.dto.PremioRifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaRequest;
import com.hydra.pica.plataforma_pica.rifa.repository.ReclamoRifaRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.rifa.service.RifaService;
import com.hydra.pica.plataforma_pica.rifa.service.VentaRifaService;
import com.hydra.pica.plataforma_pica.torneo.dto.CheckinTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.service.TorneoService;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PermisoRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Las seis pruebas de aceptación de HU-808 (T808-2) de punta a punta, contra Postgres. Sin {@code @Transactional} a
 * propósito: los avisos salen AFTER_COMMIT, y con el rollback del test no saldría ninguno. Cada test arma sus usuarios.
 * Las compras de la rifa se cobran del saldo (como en LiberacionReclamoIntegracionTest), para que haya plata que
 * liberar o devolver. Las notificaciones se entregan en el hilo del test ({@code app.notificaciones.sincronico}); que lleguen por WebSocket
 * en menos de 5 segundos lo prueba {@link NotificacionesWebSocketIntegracionTest}.
 */
@SpringBootTest(properties = "app.cobro.modo=saldo")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, EscenarioTorneo.class})
class AvisosHU808IntegracionTest {

    private static final AtomicInteger SECUENCIA = new AtomicInteger();
    private static final Instant MANIANA = Instant.now().plus(1, ChronoUnit.DAYS);
    private static final Instant EN_UNA_SEMANA = Instant.now().plus(7, ChronoUnit.DAYS);

    @Autowired private EscenarioTorneo escenario;
    @Autowired private TransactionTemplate transaccion;
    @Autowired private ResultadoService resultadoService;
    @Autowired private TorneoService torneoService;
    @Autowired private ProgramacionService programacionService;
    @Autowired private CheckinService checkinService;
    @Autowired private AvisoCheckinAbierto avisoCheckinAbierto;
    @Autowired private RifaService rifaService;
    @Autowired private VentaRifaService ventaRifaService;
    @Autowired private RifaRepository rifaRepository;
    @Autowired private ReclamoRifaRepository reclamoRifaRepository;
    @Autowired private PermisoRepository permisoRepository;
    @Autowired private JwtService jwtService;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private EmailService emailService;

    // --- rival asignado -----------------------------------------------------------------

    @Test
    @DisplayName("al generar el bracket cada participante recibe su rival, por correo y en el centro; los demás nada")
    void generarElBracketAvisaElRival() {
        Torneo t = torneo(4);
        Long local = escenario.jugador(t.semi1, "LOCAL");
        Long visitante = escenario.jugador(t.semi1, "VISITANTE");

        assertThat(notificaciones(local)).singleElement().satisfies(n -> {
            assertThat(n.get("tipo")).isEqualTo("RIVAL_ASIGNADO");
            assertThat((String) n.get("titulo")).isEqualTo("Tu rival en Semifinal: " + nombre(visitante));
            assertThat((String) n.get("enlace")).isEqualTo("/partidos/" + t.semi1);
        });
        assertThat(notificaciones(visitante)).singleElement()
                .satisfies(n -> assertThat((String) n.get("titulo")).endsWith(nombre(local)));
        for (Long otro : List.of(escenario.jugador(t.semi2, "LOCAL"), escenario.jugador(t.semi2, "VISITANTE"))) {
            assertThat(notificaciones(otro)).hasSize(1);
        }
        assertThat(notificaciones(t.organizador)).isEmpty();
        assertThat(notificaciones(t.arbitro)).isEmpty();
        verify(emailService).enviarAviso(eq(email(local)), eq("Tu rival en Semifinal: " + nombre(visitante)), anyString());
    }

    @Test
    @DisplayName("cuando el ganador llega a la final se avisa a los dos finalistas recién con el rival definido")
    void alAvanzarSeAvisaElRivalDeLaFinal() {
        Torneo t = torneo(4);
        Long ganador1 = escenario.jugador(t.semi1, "LOCAL");
        Long perdedor1 = escenario.jugador(t.semi1, "VISITANTE");
        Long ganador2 = escenario.jugador(t.semi2, "LOCAL");

        ganar(t.semi1);
        // la final todavía no tiene el otro lado: nada nuevo
        assertThat(notificaciones(ganador1)).hasSize(1);

        ganar(t.semi2);
        assertThat(notificaciones(ganador1)).hasSize(2).last()
                .satisfies(n -> assertThat((String) n.get("titulo")).isEqualTo("Tu rival en Final: " + nombre(ganador2)));
        assertThat(notificaciones(ganador2)).hasSize(2).last()
                .satisfies(n -> assertThat((String) n.get("titulo")).isEqualTo("Tu rival en Final: " + nombre(ganador1)));
        assertThat(notificaciones(perdedor1)).hasSize(1);
    }

    // --- check-in e inicio del cruce -----------------------------------------------------------

    @Test
    @DisplayName("al abrirse el check-in ambos lados reciben la hora límite una sola vez; el otro partido nada")
    void checkinAbiertoAvisaAAmbosConLaHoraLimite() {
        Torneo t = torneoConCheckinAbierto();
        Long local = escenario.jugador(t.semi1, "LOCAL");
        Long visitante = escenario.jugador(t.semi1, "VISITANTE");
        Long deOtroPartido = escenario.jugador(t.semi2, "LOCAL");

        assertThat(avisoCheckinAbierto.notificarAbiertas(Instant.now())).isEqualTo(1);
        // la segunda pasada ya lo encuentra marcado
        assertThat(avisoCheckinAbierto.notificarAbiertas(Instant.now())).isZero();

        for (Long jugador : List.of(local, visitante)) {
            assertThat(notificaciones(jugador)).filteredOn(n -> "CHECKIN_ABIERTO".equals(n.get("tipo")))
                    .singleElement().satisfies(n -> {
                        assertThat((String) n.get("cuerpo")).contains("El check-in cierra el");
                        assertThat((String) n.get("datos")).contains("cierraEn");
                    });
        }
        assertThat(notificaciones(deOtroPartido)).noneMatch(n -> "CHECKIN_ABIERTO".equals(n.get("tipo")));
    }

    @Test
    @DisplayName("con el segundo check-in empieza el cruce y se avisa a los dos lados; con el primero todavía no")
    void inicioDelCruceAvisaAAmbos() {
        Torneo t = torneoConCheckinAbierto();
        Long local = escenario.jugador(t.semi1, "LOCAL");
        Long visitante = escenario.jugador(t.semi1, "VISITANTE");

        checkinService.hacer(local, t.semi1);
        assertThat(notificaciones(local)).noneMatch(n -> "CRUCE_INICIADO".equals(n.get("tipo")));

        checkinService.hacer(visitante, t.semi1);
        for (Long jugador : List.of(local, visitante)) {
            assertThat(notificaciones(jugador)).filteredOn(n -> "CRUCE_INICIADO".equals(n.get("tipo"))).hasSize(1);
        }
        assertThat(notificaciones(escenario.jugador(t.semi2, "LOCAL")))
                .noneMatch(n -> "CRUCE_INICIADO".equals(n.get("tipo")));
    }

    // --- reclamo de rifa ---------------------------------------------------------------------

    @Test
    @DisplayName("al cerrar la disputa sin fraude: fallo y justificación al reclamante, organizador y ganador; el monto a quien cobra")
    void reclamoSinFraude() throws Exception {
        Rifa r = rifaSorteadaConReclamo();

        resolver(r, "SIN_FRAUDE", "La semilla de drand coincide con la publicada");

        for (Usuario involucrado : List.of(r.reclamante, r.organizador, r.ganador)) {
            assertThat(notificaciones(involucrado.getId())).singleElement().satisfies(n -> {
                assertThat(n.get("tipo")).isEqualTo("RECLAMO_RESUELTO");
                assertThat((String) n.get("cuerpo"))
                        .contains("Sin fraude: el reclamo fue rechazado.")
                        .contains("La semilla de drand coincide con la publicada")
                        .contains(r.titulo);
            });
        }
        assertThat(notificaciones(r.ajeno.getId())).isEmpty();

        liberar(r);
        assertThat(notificaciones(r.ganador.getId())).hasSize(2).last().satisfies(n -> {
            assertThat((String) n.get("titulo")).isEqualTo("Se acreditaron $ 1.000,00 en tu billetera");
            assertThat((String) n.get("datos")).contains("\"monto\": 1000");
        });
        // el reclamante no cobra nada si no hubo fraude
        assertThat(notificaciones(r.reclamante.getId())).hasSize(1);
        assertThat(notificaciones(r.ajeno.getId())).isEmpty();
    }

    @Test
    @DisplayName("con fraude confirmado cada comprador recibe el aviso del reintegro con lo que se le devolvió")
    void reclamoConFraude() throws Exception {
        Rifa r = rifaSorteadaConReclamo();

        resolver(r, "FRAUDE_CONFIRMADO", "El organizador conocía la semilla antes del cierre");
        liberar(r);

        for (Usuario comprador : List.of(r.reclamante, r.ganador)) {
            assertThat(notificaciones(comprador.getId())).hasSize(2).last().satisfies(n -> {
                assertThat((String) n.get("titulo")).isEqualTo("Se acreditaron $ 550,00 en tu billetera");
                assertThat((String) n.get("cuerpo")).contains("te devolvimos lo que pagaste");
            });
        }
        assertThat(notificaciones(r.organizador.getId())).singleElement()
                .satisfies(n -> assertThat((String) n.get("cuerpo")).contains("Fraude confirmado"));
        assertThat(notificaciones(r.ajeno.getId())).isEmpty();
    }

    // --- escenarios ----------------------------------------------------------------------------

    private record Torneo(Long id, Long organizador, Long arbitro, Long semi1, Long semi2) {
    }

    private Torneo torneo(int jugadores) {
        String sufijo = "h808_" + SECUENCIA.incrementAndGet();
        return transaccion.execute(estado -> {
            Long organizador = escenario.usuario("org_" + sufijo, "ORGANIZADOR").getId();
            Long arbitro = escenario.usuario("juez_" + sufijo, "ARBITRO").getId();
            Long torneo = escenario.individualEnCurso(organizador, arbitro, "Copa " + sufijo, "jug_" + sufijo + "_",
                    jugadores);
            return new Torneo(torneo, organizador, arbitro, escenario.partido(torneo, 1, 1),
                    escenario.partido(torneo, 1, 2));
        });
    }

    /** El check-in abre 30 minutos antes y dura 20; la primera semifinal se juega en 25: la ventana abrió hace 5. */
    private Torneo torneoConCheckinAbierto() {
        Torneo t = torneo(4);
        torneoService.configurarCheckin(t.organizador, t.id, new CheckinTorneoRequest(30, 20, null));
        transaccion.executeWithoutResult(estado -> escenario.empezadoAyer(t.id));
        programacionService.programar(t.organizador, t.semi1,
                Instant.now().plus(25, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS));
        return t;
    }

    private void ganar(Long partido) {
        resultadoService.cargar(escenario.jugador(partido, "LOCAL"), partido, new ResultadoRequest(2, 0, null, null));
        resultadoService.aceptar(escenario.jugador(partido, "VISITANTE"), partido);
    }

    private record Rifa(Long id, String titulo, Long reclamo, Usuario organizador, Usuario ganador, Usuario reclamante,
                        Usuario ajeno, Usuario soporte) {
    }

    /** Dos compradores a $ 500 más 10 %; el número 1 ganó el premio único y el sorteo fue hace 50 horas. */
    private Rifa rifaSorteadaConReclamo() {
        String sufijo = "h808r_" + SECUENCIA.incrementAndGet();
        Usuario organizador = transaccion.execute(e -> escenario.usuario("org_" + sufijo, "ORGANIZADOR"));
        Usuario ganador = transaccion.execute(e -> escenario.usuario("gan_" + sufijo, "PARTICIPANTE"));
        Usuario reclamante = transaccion.execute(e -> escenario.usuario("rec_" + sufijo, "PARTICIPANTE"));
        Usuario ajeno = transaccion.execute(e -> escenario.usuario("aje_" + sufijo, "PARTICIPANTE"));
        Usuario soporte = transaccion.execute(e -> escenario.usuario("sop_" + sufijo, "SOPORTE"));
        saldo(ganador.getId());
        saldo(reclamante.getId());

        String titulo = "Rifa " + sufijo;
        RifaDetalle creada = rifaService.crear(organizador.getId(), new RifaRequest(titulo, null, 1, 50,
                new BigDecimal("500"), MANIANA, EN_UNA_SEMANA, EN_UNA_SEMANA.plus(1, ChronoUnit.DAYS), null, null));
        rifaService.reemplazarPremios(organizador.getId(), creada.id(),
                List.of(new PremioRifaRequest(BigDecimal.valueOf(100), null)));
        rifaService.publicar(organizador.getId(), creada.id());
        jdbc.update("UPDATE rifa SET venta_desde = now() - interval '1 hour' WHERE id = ?", creada.id());
        ventaRifaService.reservar(ganador.getId(), creada.id(), List.of(1));
        ventaRifaService.comprar(ganador.getId(), creada.id(), List.of(1), "gan-" + creada.id());
        ventaRifaService.reservar(reclamante.getId(), creada.id(), List.of(2));
        ventaRifaService.comprar(reclamante.getId(), creada.id(), List.of(2), "rec-" + creada.id());
        jdbc.update("UPDATE premio_rifa SET numero_ganador = 1 WHERE rifa_id = ? AND puesto = 1", creada.id());
        jdbc.update("""
                UPDATE rifa SET estado = 'SORTEADA', venta_desde = now() - interval '10 days',
                                venta_hasta = now() - interval '5 days', sorteada_en = now() - interval '50 hours',
                                semilla_obtenida_en = now() - interval '50 hours'
                WHERE id = ?""", creada.id());

        Long reclamo = transaccion.execute(e -> {
            ReclamoRifa nuevo = new ReclamoRifa();
            nuevo.setRifa(rifaRepository.getReferenceById(creada.id()));
            nuevo.setUsuario(reclamante);
            nuevo.setMotivo("Revisión del sorteo");
            nuevo.setEstado(EstadoReclamo.PENDIENTE);
            return reclamoRifaRepository.saveAndFlush(nuevo).getId();
        });
        return new Rifa(creada.id(), titulo, reclamo, organizador, ganador, reclamante, ajeno, soporte);
    }

    private void resolver(Rifa r, String decision, String justificacion) throws Exception {
        mockMvc.perform(post("/api/v1/admin/reclamos/" + r.reclamo + "/resolucion")
                        .header("Authorization", "Bearer " + token(r.soporte))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("decision", decision, "resolucion", justificacion))))
                .andExpect(status().isOk());
    }

    private void liberar(Rifa r) throws Exception {
        mockMvc.perform(post("/api/v1/admin/reclamos/" + r.reclamo + "/liberar")
                        .header("Authorization", "Bearer " + token(r.soporte)))
                .andExpect(status().isOk());
    }

    private String token(Usuario usuario) {
        return jwtService.generarAccessToken(usuario.getId(), usuario.getUsername(), List.of("SOPORTE"),
                List.copyOf(permisoRepository.findCodigosByUsuarioId(usuario.getId())), usuario.getVersionSesion());
    }

    private void saldo(Long usuarioId) {
        jdbc.update("""
                INSERT INTO billetera (usuario_id, saldo_disponible) VALUES (?, 10000)
                ON CONFLICT (usuario_id) DO UPDATE SET saldo_disponible = excluded.saldo_disponible""", usuarioId);
    }

    private List<Map<String, Object>> notificaciones(Long usuarioId) {
        return jdbc.queryForList("""
                SELECT tipo, titulo, cuerpo, enlace, datos::text AS datos FROM notificacion
                WHERE usuario_id = ? ORDER BY id""", usuarioId);
    }

    private String nombre(Long usuarioId) {
        return jdbc.queryForObject("""
                SELECT p.nombres || ' ' || p.apellidos FROM usuario u JOIN persona p ON p.id = u.persona_id
                WHERE u.id = ?""", String.class, usuarioId);
    }

    private String email(Long usuarioId) {
        return jdbc.queryForObject("SELECT email FROM usuario WHERE id = ?", String.class, usuarioId);
    }
}
