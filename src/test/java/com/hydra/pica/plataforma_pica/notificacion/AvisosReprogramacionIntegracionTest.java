package com.hydra.pica.plataforma_pica.notificacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.hydra.pica.plataforma_pica.EscenarioTorneo;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.torneo.dto.ReprogramacionRequest;
import com.hydra.pica.plataforma_pica.torneo.service.RechazoReprogramacionService;
import com.hydra.pica.plataforma_pica.torneo.service.ReprogramacionTorneoService;
import com.hydra.pica.plataforma_pica.torneo.service.VencimientoReprogramacion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Los avisos de la reprogramación de un torneo (CR-01-4, PICA-393) por el centro de notificaciones, contra Postgres.
 * Sin {@code @Transactional} a propósito, como AvisosHU808IntegracionTest: los avisos salen AFTER_COMMIT. Cada test arma
 * su torneo: dos jugadores confirmados, cupo 4 y mínimo 2, con la inscripción abierta. Las notificaciones se entregan
 * en el hilo del test ({@code app.notificaciones.sincronico}).
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, EscenarioTorneo.class})
class AvisosReprogramacionIntegracionTest {

    private static final AtomicInteger SECUENCIA = new AtomicInteger();

    @Autowired private ReprogramacionTorneoService reprogramacionService;
    @Autowired private RechazoReprogramacionService rechazoService;
    @Autowired private VencimientoReprogramacion vencimiento;
    @Autowired private EscenarioTorneo escenario;
    @Autowired private TransactionTemplate transaccion;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private EmailService emailService;

    private Long organizador;
    private long torneo;
    private long[] inscripcion;
    private Long[] jugador;

    @BeforeEach
    void torneoConDosInscriptos() {
        String sufijo = "reprog_av_" + SECUENCIA.incrementAndGet() + "_" + System.nanoTime();
        transaccion.executeWithoutResult(estado -> {
            organizador = escenario.usuario("org_" + sufijo, "ORGANIZADOR").getId();
            torneo = jdbc.queryForObject("""
                    INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                        tipo_participante, cupo_maximo, cupo_minimo, valor_inscripcion,
                                        pct_gastos_administrativos, cierre_inscripcion_en, fecha_inicio, estado,
                                        publicado_en, slug)
                    VALUES (?, ?, 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE', 'INDIVIDUAL', 4, 2, 0, 10,
                            now() + interval '1 day', now() + interval '7 days', 'INSCRIPCION_ABIERTA', now(), ?)
                    RETURNING id
                    """, Long.class, organizador, "Copa " + sufijo, "copa-" + sufijo.replace('_', '-'));
            jugador = new Long[2];
            inscripcion = new long[2];
            for (int i = 0; i < 2; i++) {
                jugador[i] = escenario.usuario("jug" + i + "_" + sufijo, "PARTICIPANTE").getId();
                inscripcion[i] = jdbc.queryForObject("""
                        INSERT INTO inscripcion (torneo_id, usuario_id, estado, reglamento_aceptado_en)
                        VALUES (?, ?, 'CONFIRMADA', now()) RETURNING id
                        """, Long.class, torneo, jugador[i]);
            }
        });
    }

    @Test
    @DisplayName("al reprogramar cada inscripto recibe la nueva fecha y el plazo, por correo y en el centro")
    void reprogramarAvisaALosInscriptos() {
        reprogramar();

        for (Long j : jugador) {
            assertThat(notificaciones(j)).singleElement().satisfies(n -> {
                assertThat(n.get("tipo")).isEqualTo("TORNEO_REPROGRAMADO");
                assertThat((String) n.get("titulo")).startsWith("Cambió la fecha de Copa ");
                assertThat((String) n.get("cuerpo")).contains("Motivo: Lluvia", "puede bajarse desde PICA hasta el",
                        "los gastos administrativos no se devuelven");
                assertThat((String) n.get("enlace")).startsWith("/torneos/copa-");
            });
            verify(emailService).enviarAviso(eq(email(j)), contains("Cambió la fecha de"), anyString());
        }
        assertThat(notificaciones(organizador)).isEmpty();
    }

    @Test
    @DisplayName("el recordatorio sale una sola vez, un día antes, a los que siguen inscriptos")
    void elRecordatorioSaleUnaVezALosQueSiguen() {
        reprogramar();
        rechazoService.rechazar(jugador[0], inscripcion[0]);
        jdbc.update("UPDATE reprogramacion SET rechazo_hasta = now() + interval '12 hours' WHERE torneo_id = ?",
                torneo);

        assertThat(vencimiento.recordar()).isPositive();
        assertThat(vencimiento.recordar()).isZero();

        assertThat(tipos(jugador[1])).containsExactly("TORNEO_REPROGRAMADO", "REPROGRAMACION_POR_VENCER");
        assertThat(tipos(jugador[0])).containsExactly("TORNEO_REPROGRAMADO");
    }

    @Test
    @DisplayName("con más de un día por delante todavía no hay recordatorio")
    void lejosDelPlazoNoHayRecordatorio() {
        reprogramar();

        vencimiento.recordar();

        assertThat(tipos(jugador[0])).containsExactly("TORNEO_REPROGRAMADO");
    }

    @Test
    @DisplayName("si una baja lo deja por debajo del mínimo, el organizador se entera y el torneo sigue")
    void bajoElMinimoAvisaAlOrganizador() {
        reprogramar();

        rechazoService.rechazar(jugador[0], inscripcion[0]);

        assertThat(notificaciones(organizador)).singleElement().satisfies(n -> {
            assertThat(n.get("tipo")).isEqualTo("INSCRIPTOS_BAJO_EL_MINIMO");
            assertThat((String) n.get("cuerpo")).contains("inscripciones confirmadas, 1; hacen falta 2",
                    "no se cancela solo");
        });
        assertThat(jdbc.queryForObject("SELECT estado FROM torneo WHERE id = ?", String.class, torneo))
                .isEqualTo("INSCRIPCION_ABIERTA");
    }

    @Test
    @DisplayName("al vencer el plazo el organizador recibe cuántos se bajaron y cómo quedó la inscripción")
    void alVencerElPlazoAvisaAlOrganizador() {
        reprogramar();
        rechazoService.rechazar(jugador[0], inscripcion[0]);
        jdbc.update("UPDATE reprogramacion SET rechazo_hasta = now() - interval '1 minute' WHERE torneo_id = ?",
                torneo);

        vencimiento.resolverVencidas();

        assertThat(notificaciones(organizador)).last().satisfies(n -> {
            assertThat(n.get("tipo")).isEqualTo("REPROGRAMACION_RESUELTA");
            assertThat((String) n.get("cuerpo")).contains("Se bajó 1 inscripción", "Inscripciones confirmadas: 1.",
                    "La inscripción está abierta hasta el");
        });
    }

    // --- ayudas -------------------------------------------------------------------------------------

    private void reprogramar() {
        Instant ahora = Instant.now();
        reprogramacionService.reprogramar(organizador, torneo, new ReprogramacionRequest(
                ahora.plus(20, ChronoUnit.DAYS), null, ahora.plus(10, ChronoUnit.DAYS),
                ahora.plus(5, ChronoUnit.DAYS), "Lluvia"));
    }

    private List<Map<String, Object>> notificaciones(Long usuarioId) {
        return jdbc.queryForList("SELECT tipo, titulo, cuerpo, enlace FROM notificacion WHERE usuario_id = ? ORDER BY id",
                usuarioId);
    }

    private List<String> tipos(Long usuarioId) {
        return jdbc.queryForList("SELECT tipo FROM notificacion WHERE usuario_id = ? ORDER BY id", String.class,
                usuarioId);
    }

    private String email(Long usuarioId) {
        return jdbc.queryForObject("SELECT email FROM usuario WHERE id = ?", String.class, usuarioId);
    }
}
