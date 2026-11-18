package com.hydra.pica.plataforma_pica.torneo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.util.Map;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.inscripcion.service.InscripcionService;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.event.TorneoEstadoCambiado;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * La tarea que cierra las inscripciones vencidas (HU-206, PICA-213) contra Postgres, y que cancela las que vencen por
 * debajo del mínimo para jugar (PICA-447). Los tests la llaman a mano: la programación está apagada. Cada test hace
 * rollback al terminar.
 */
@SpringBootTest(properties = "app.cobro.modo=saldo")
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
@RecordApplicationEvents
class CierreAutomaticoIntegracionTest {

    @Autowired private CierreAutomatico cierreAutomatico;
    @MockitoSpyBean private InscripcionTorneoService inscripcionTorneoService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private InscripcionService inscripcionService;
    @Autowired private ApplicationEvents eventos;
    @Autowired private EntityManager entityManager;

    private Long organizador;

    @BeforeEach
    void escenario() {
        organizador = usuario("org_cierre_auto").getId();
        // los torneos que otros tests dejaron en la base no cuentan: dentro de este test, ninguno está vencido
        jdbc.update("""
                UPDATE torneo SET fecha_inicio = greatest(fecha_inicio, now() + interval '2 days'),
                                  cierre_inscripcion_en = now() + interval '1 day'
                WHERE estado = 'INSCRIPCION_ABIERTA' AND cierre_inscripcion_en <= now()
                """);
    }

    @Test
    void cierraConElMotivoFechaSoloLasInscripcionesAbiertasConLaFechaVencida() {
        long vencido = torneo("INSCRIPCION_ABIERTA", "- interval '1 minute'");
        long vigente = torneo("INSCRIPCION_ABIERTA", "+ interval '1 day'");
        long borrador = torneo("BORRADOR", "- interval '1 minute'");
        long eliminado = torneo("INSCRIPCION_ABIERTA", "- interval '1 minute'");
        jdbc.update("UPDATE torneo SET eliminado_en = now() WHERE id = ?", eliminado);

        assertThat(cierreAutomatico.cerrarVencidas()).isEqualTo(1);

        assertThat(estadoDe(vencido)).isEqualTo("INSCRIPCION_CERRADA");
        assertThat(estadoDe(vigente)).isEqualTo("INSCRIPCION_ABIERTA");
        assertThat(estadoDe(borrador)).isEqualTo("BORRADOR");
        assertThat(estadoDe(eliminado)).isEqualTo("INSCRIPCION_ABIERTA");
        Map<String, Object> auditoria = jdbc.queryForMap("""
                SELECT usuario_id, detalle->>'motivo' AS motivo FROM auditoria
                WHERE accion = 'CIERRE_INSCRIPCION' AND entidad_id = ?
                """, String.valueOf(vencido));
        assertThat(auditoria).containsEntry("motivo", "FECHA").containsEntry("usuario_id", null);
    }

    @Test
    void cierraAunqueQuedenLugaresYCancelaLasPendientes() {
        long vencido = torneo("INSCRIPCION_ABIERTA", "- interval '1 minute'");
        long pendiente = jdbc.queryForObject("""
                INSERT INTO inscripcion (torneo_id, usuario_id, estado, reglamento_aceptado_en)
                VALUES (?, ?, 'PENDIENTE_PAGO', now()) RETURNING id
                """, Long.class, vencido, usuario("pend_cierre_auto").getId());

        cierreAutomatico.cerrarVencidas();

        assertThat(estadoDe(vencido)).isEqualTo("INSCRIPCION_CERRADA");
        assertThat(jdbc.queryForObject("SELECT estado FROM inscripcion WHERE id = ?", String.class, pendiente))
                .isEqualTo("CANCELADA");
    }

    @Test
    void correrlaOtraVezNoVuelveACerrarNiCambiaElSello() {
        long vencido = torneo("INSCRIPCION_ABIERTA", "- interval '1 minute'");
        cierreAutomatico.cerrarVencidas();
        String sello = jdbc.queryForObject("SELECT lista_hash FROM torneo WHERE id = ?", String.class, vencido);

        assertThat(cierreAutomatico.cerrarVencidas()).isZero();

        assertThat(jdbc.queryForObject("SELECT lista_hash FROM torneo WHERE id = ?", String.class, vencido)).isEqualTo(sello);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auditoria WHERE accion = 'CIERRE_INSCRIPCION' AND entidad_id = ?",
                Integer.class, String.valueOf(vencido))).isEqualTo(1);
    }

    @Test
    void siFallaConUnTorneoIgualCierraLosDemas() {
        long falla = torneo("INSCRIPCION_ABIERTA", "- interval '2 minutes'");
        long cierra = torneo("INSCRIPCION_ABIERTA", "- interval '1 minute'");
        doThrow(new IllegalStateException("falla de prueba")).when(inscripcionTorneoService).cerrarSiVencio(eq(falla), any());

        assertThat(cierreAutomatico.cerrarVencidas()).isEqualTo(1);

        assertThat(estadoDe(falla)).isEqualTo("INSCRIPCION_ABIERTA");
        assertThat(estadoDe(cierra)).isEqualTo("INSCRIPCION_CERRADA");
    }

    // --- por debajo del mínimo (PICA-447) ------------------------------------

    @Test
    void vencidoPorDebajoDelMinimoYSinGastosSeCancelaYCadaUnoRecuperaLoQuePago() {
        long vencido = torneoConPagos(1);
        long pendiente = inscripcion(vencido, usuario("pend_bajo_min").getId(), "PENDIENTE_PAGO");
        long borrador = inscripcion(vencido, usuario("borr_bajo_min").getId(), "BORRADOR");
        vencer(vencido);

        assertThat(cierreAutomatico.cerrarVencidas()).isZero();
        entityManager.flush();

        assertThat(estadoDe(vencido)).isEqualTo("CANCELADO");
        assertThat(jdbc.queryForObject("SELECT lista_hash FROM torneo WHERE id = ?", String.class, vencido)).isNull();
        assertThat(jdbc.queryForList("SELECT estado FROM inscripcion WHERE torneo_id = ? AND usuario_id = ?",
                String.class, vencido, pagador)).containsExactly("REINTEGRADA");
        assertThat(jdbc.queryForList("SELECT estado FROM inscripcion WHERE id IN (?, ?)", String.class,
                pendiente, borrador)).containsOnly("CANCELADA");
        assertThat(jdbc.queryForObject("SELECT saldo_disponible FROM billetera WHERE usuario_id = ?",
                BigDecimal.class, pagador)).isEqualByComparingTo("5000.00");
        assertThat(jdbc.queryForObject("SELECT estado FROM cancelacion WHERE torneo_id = ?", String.class, vencido))
                .isEqualTo("AUTOMATICA");
        Map<String, Object> auditoria = jdbc.queryForMap("""
                SELECT usuario_id, detalle->>'motivo' AS motivo FROM auditoria
                WHERE accion = 'CANCELACION' AND entidad = 'TORNEO' AND entidad_id = ?
                """, String.valueOf(vencido));
        assertThat(auditoria).containsEntry("motivo", "INSCRIPTOS_INSUFICIENTES").containsEntry("usuario_id", null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auditoria WHERE accion = 'CIERRE_INSCRIPCION' "
                + "AND entidad_id = ?", Integer.class, String.valueOf(vencido))).isZero();
        assertThat(eventos.stream(TorneoEstadoCambiado.class))
                .contains(new TorneoEstadoCambiado(vencido, EstadoTorneo.INSCRIPCION_ABIERTA, EstadoTorneo.CANCELADO));
    }

    @Test
    void vencidoPorDebajoDelMinimoYConGastosPagadosQuedaEnRevisionSinTocarNadaMas() {
        long vencido = torneoConPagos(1);
        jdbc.update("""
                INSERT INTO gasto (torneo_id, gasto_id, descripcion, monto, monto_abonado, fecha_abonado, comprobante)
                VALUES (?, 1, 'Cancha', 500, 500, now(), 'factura.pdf')""", vencido);
        vencer(vencido);

        cierreAutomatico.cerrarVencidas();
        entityManager.flush();

        assertThat(estadoDe(vencido)).isEqualTo("CANCELACION_EN_REVISION");
        assertThat(jdbc.queryForList("SELECT estado FROM inscripcion WHERE torneo_id = ? AND usuario_id = ?",
                String.class, vencido, pagador)).containsExactly("CONFIRMADA");
        assertThat(jdbc.queryForObject("SELECT saldo_disponible FROM billetera WHERE usuario_id = ?",
                BigDecimal.class, pagador)).isEqualByComparingTo("3900.00");
        assertThat(jdbc.queryForObject("SELECT estado FROM cancelacion WHERE torneo_id = ?", String.class, vencido))
                .isEqualTo("EN_REVISION");
        assertThat(jdbc.queryForObject("""
                SELECT detalle->>'motivo' FROM auditoria
                WHERE accion = 'CANCELACION' AND entidad = 'TORNEO' AND entidad_id = ? AND usuario_id IS NULL
                """, String.class, String.valueOf(vencido))).isEqualTo("INSCRIPTOS_INSUFICIENTES");
        assertThat(eventos.stream(TorneoEstadoCambiado.class)).contains(new TorneoEstadoCambiado(vencido,
                EstadoTorneo.INSCRIPCION_ABIERTA, EstadoTorneo.CANCELACION_EN_REVISION));
    }

    @Test
    void unGastoPrevistoSinPagarNoFrenaLaCancelacion() {
        long vencido = torneoConPagos(1);
        jdbc.update("INSERT INTO gasto (torneo_id, gasto_id, descripcion, monto) VALUES (?, 1, 'Cancha', 500)",
                vencido);
        vencer(vencido);

        cierreAutomatico.cerrarVencidas();

        assertThat(estadoDe(vencido)).isEqualTo("CANCELADO");
    }

    @Test
    void vencidoEnElMinimoCierraComoSiempre() {
        long vencido = torneoConPagos(2);
        vencer(vencido);

        assertThat(cierreAutomatico.cerrarVencidas()).isEqualTo(1);

        assertThat(estadoDe(vencido)).isEqualTo("INSCRIPCION_CERRADA");
        assertThat(jdbc.queryForObject("SELECT lista_hash FROM torneo WHERE id = ?", String.class, vencido)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cancelacion WHERE torneo_id = ?", Integer.class, vencido))
                .isZero();
    }

    // --- ayudas -------------------------------------------------------------

    private Long pagador;

    /**
     * Un torneo publicado y abierto, con {@code pagos} inscripciones individuales pagadas desde la billetera ($1000
     * más el 10 % de gastos administrativos cada una, con $5000 de saldo); el primero que paga queda en
     * {@link #pagador}.
     */
    private long torneoConPagos(int pagos) {
        long id = torneo("INSCRIPCION_ABIERTA", "+ interval '1 day'", 0);
        jdbc.update("UPDATE torneo SET publicado_en = now(), pct_gastos_administrativos = 10 WHERE id = ?", id);
        for (int i = 0; i < pagos; i++) {
            Long usuario = usuario("pago_" + id + "_" + i).getId();
            jdbc.update("INSERT INTO billetera (usuario_id, saldo_disponible) VALUES (?, 5000)", usuario);
            inscripcionService.pagar(usuario, inscripcion(id, usuario, "PENDIENTE_PAGO"));
            if (i == 0) {
                pagador = usuario;
            }
        }
        return id;
    }

    // pagar dejó el torneo en el contexto de persistencia: sin limpiarlo, el cierre lo vería sin vencer
    private void vencer(long torneoId) {
        entityManager.flush();
        jdbc.update("UPDATE torneo SET cierre_inscripcion_en = now() - interval '1 minute' WHERE id = ?", torneoId);
        entityManager.clear();
    }

    private long inscripcion(long torneoId, Long usuarioId, String estado) {
        return jdbc.queryForObject("""
                INSERT INTO inscripcion (torneo_id, usuario_id, estado, reglamento_aceptado_en)
                VALUES (?, ?, ?, CASE WHEN ? = 'BORRADOR' THEN NULL ELSE now() END) RETURNING id
                """, Long.class, torneoId, usuarioId, estado, estado);
    }

    private String estadoDe(long torneoId) {
        return jdbc.queryForObject("SELECT estado FROM torneo WHERE id = ?", String.class, torneoId);
    }

    // el cierre relativo a ahora, por ejemplo "- interval '1 minute'"; con las 2 confirmadas que hacen falta
    private long torneo(String estado, String cierre) {
        return torneo(estado, cierre, 2);
    }

    private long torneo(String estado, String cierre, int confirmadas) {
        long id = jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, valor_inscripcion,
                                    pct_pozo_competidores, pct_premio_rifa, cierre_inscripcion_en, fecha_inicio, estado)
                VALUES (?, 'Torneo', 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE', 'INDIVIDUAL', 8, 2,
                        1000, 50, 10, now() %s, now() + interval '7 days', ?)
                RETURNING id
                """.formatted(cierre), Long.class, organizador, estado);
        for (int i = 0; i < confirmadas; i++) {
            inscripcion(id, usuario("conf_" + id + "_" + i).getId(), "CONFIRMADA");
        }
        return id;
    }

    private Usuario usuario(String nombre) {
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
        return usuarioRepository.save(usuario);
    }
}
