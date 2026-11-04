package com.hydra.pica.plataforma_pica.torneo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import java.util.Map;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * La tarea que cierra las inscripciones vencidas (HU-206, PICA-213) contra Postgres. Los tests la llaman a mano: la
 * programación está apagada. Cada test hace rollback al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class CierreAutomaticoIntegracionTest {

    @Autowired private CierreAutomatico cierreAutomatico;
    @MockitoSpyBean private InscripcionTorneoService inscripcionTorneoService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;

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

    // --- ayudas -------------------------------------------------------------

    private String estadoDe(long torneoId) {
        return jdbc.queryForObject("SELECT estado FROM torneo WHERE id = ?", String.class, torneoId);
    }

    // el cierre relativo a ahora, por ejemplo "- interval '1 minute'"
    private long torneo(String estado, String cierre) {
        return jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, valor_inscripcion,
                                    pct_pozo_competidores, pct_premio_rifa, cierre_inscripcion_en, fecha_inicio, estado)
                VALUES (?, 'Torneo', 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE', 'INDIVIDUAL', 8, 2,
                        1000, 50, 10, now() %s, now() + interval '7 days', ?)
                RETURNING id
                """.formatted(cierre), Long.class, organizador, estado);
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
