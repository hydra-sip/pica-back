package com.hydra.pica.plataforma_pica.torneo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.inscripcion.service.InscripcionService;
import com.hydra.pica.plataforma_pica.torneo.dto.GastoDeclarado;
import com.hydra.pica.plataforma_pica.torneo.dto.ResolucionCancelacion;
import com.hydra.pica.plataforma_pica.torneo.dto.SolicitudCancelacion;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * La revisión de soporte de una cancelación con gastos (HU-605, PICA-348). Tres jugadores pagan con saldo $1000 más 10 %
 * de gastos administrativos: hay $3300 en custodia y el pozo es $3000. El organizador cancela declarando $450,50 de
 * gastos y el torneo queda en revisión. Cada test hace rollback al terminar.
 */
@SpringBootTest(properties = "app.cobro.modo=saldo")
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class RevisionCancelacionIntegracionTest {

    private static final byte[] PDF = "%PDF-1.7\nfactura".getBytes(StandardCharsets.US_ASCII);

    @Autowired private RevisionCancelacionService revisionCancelacionService;
    @Autowired private CancelacionTorneoService cancelacionTorneoService;
    @Autowired private InscripcionService inscripcionService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private Long organizador;
    private Long soporte;
    private long torneo;
    private final Long[] jugador = new Long[3];
    private final long[] inscripcion = new long[3];

    @BeforeEach
    void escenario() {
        organizador = usuario("org_rev");
        soporte = usuario("sop_rev");
        torneo = torneo();
        for (int i = 0; i < 3; i++) {
            jugador[i] = usuario("jug_rev_" + i);
            saldo(jugador[i], "5000.00");
            inscripcion[i] = jdbc.queryForObject("""
                    INSERT INTO inscripcion (torneo_id, usuario_id, estado, reglamento_aceptado_en)
                    VALUES (?, ?, 'PENDIENTE_PAGO', now()) RETURNING id
                    """, Long.class, torneo, jugador[i]);
            inscripcionService.pagar(jugador[i], inscripcion[i]);
        }
        cancelacionTorneoService.cancelar(organizador, torneo, new SolicitudCancelacion("Se inundó la cancha",
                List.of(new GastoDeclarado(new BigDecimal("300.00"), "Alquiler de la cancha", PDF),
                        new GastoDeclarado(new BigDecimal("150.50"), "Árbitro", PDF))));
        entityManager.flush();
        entityManager.clear();
    }

    // --- rechazar ------------------------------------------------------------------------------------

    @Test
    void alRechazarCadaUnoRecuperaTodoYElTorneoQuedaCancelado() {
        ResolucionCancelacion resolucion = revisionCancelacionService.rechazar(soporte, torneo,
                "Los comprobantes no son del torneo");

        assertThat(resolucion.estado()).isEqualTo("RECHAZADA");
        assertThat(resolucion.reintegrado()).isEqualByComparingTo("3300.00");
        assertThat(resolucion.retenido()).isEqualByComparingTo("0");
        assertThat(resolucion.alOrganizador()).isEqualByComparingTo("0");
        for (Long j : jugador) {
            assertBilletera(j, "5000.00", "0.00");
        }
        assertThat(jdbc.queryForObject("SELECT estado FROM torneo WHERE id = ?", String.class, torneo))
                .isEqualTo("CANCELADO");
        assertThat(jdbc.queryForObject("SELECT estado FROM custodia WHERE torneo_id = ?", String.class, torneo))
                .isEqualTo("REINTEGRADA");
        assertThat(jdbc.queryForList("SELECT estado FROM inscripcion WHERE torneo_id = ?", String.class, torneo))
                .containsOnly("REINTEGRADA");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM billetera b JOIN usuario u ON u.id = b.usuario_id "
                + "WHERE u.id = ? AND b.saldo_disponible > 0", Long.class, organizador)).isZero();
    }

    @Test
    void laCancelacionQuedaRechazadaConQuienLaRevisoYElMotivoYAuditada() {
        revisionCancelacionService.rechazar(soporte, torneo, "Los comprobantes no son del torneo");

        Map<String, Object> cancelacion = jdbc.queryForMap(
                "SELECT estado, revisor_id, revisada_en, motivo_rechazo FROM cancelacion WHERE torneo_id = ?", torneo);
        assertThat(cancelacion).containsEntry("estado", "RECHAZADA").containsEntry("revisor_id", soporte)
                .containsEntry("motivo_rechazo", "Los comprobantes no son del torneo");
        assertThat(cancelacion.get("revisada_en")).isNotNull();
        assertThat(jdbc.queryForMap("""
                SELECT usuario_id, detalle->>'estado' AS estado FROM auditoria
                WHERE accion = 'CANCELACION' AND entidad = 'TORNEO' AND entidad_id = ? ORDER BY id DESC LIMIT 1
                """, String.valueOf(torneo))).containsEntry("usuario_id", soporte).containsEntry("estado", "RECHAZADA");
    }

    @Test
    void sinMotivoNoSeRechaza() {
        assertThatThrownBy(() -> revisionCancelacionService.rechazar(soporte, torneo, " "))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCodigo()).isEqualTo(CodigoError.VALIDACION);
                    assertThat(e.getPropiedades().get("errores")).asInstanceOf(InstanceOfAssertFactories.LIST)
                            .contains(new ErrorCampo("motivo", ErrorCampo.Codigo.REQUERIDO,
                                    "Indicá el motivo del rechazo"));
                });
        assertThat(jdbc.queryForObject("SELECT estado FROM torneo WHERE id = ?", String.class, torneo))
                .isEqualTo("CANCELACION_EN_REVISION");
    }

    @Test
    void unaCancelacionSeResuelveUnaSolaVez() {
        revisionCancelacionService.rechazar(soporte, torneo, "No corresponde");
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> revisionCancelacionService.rechazar(soporte, torneo, "Otra vez"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCodigo()).isEqualTo(CodigoError.CANCELACION_YA_RESUELTA);
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getPropiedades()).containsKey("estado");
                });
        assertBilletera(jugador[0], "5000.00", "0.00");
    }

    @Test
    void unaCancelacionAutomaticaNoSeRevisa() {
        long otro = torneo();
        cancelacionTorneoService.cancelar(organizador, otro, new SolicitudCancelacion("Lluvia", List.of()));
        entityManager.flush();
        entityManager.clear();

        assertError(() -> revisionCancelacionService.rechazar(soporte, otro, "No corresponde"),
                HttpStatus.CONFLICT, CodigoError.CANCELACION_YA_RESUELTA);
    }

    @Test
    void elOrganizadorNoRevisaSuPropiaCancelacion() {
        assertError(() -> revisionCancelacionService.rechazar(organizador, torneo, "No corresponde"),
                HttpStatus.CONFLICT, CodigoError.CANCELACION_PROPIA);
    }

    @Test
    void unTorneoSinCancelacionNoSeEncuentra() {
        long sinCancelar = torneo();

        assertError(() -> revisionCancelacionService.rechazar(soporte, sinCancelar, "No corresponde"),
                HttpStatus.NOT_FOUND, CodigoError.CANCELACION_NO_ENCONTRADA);
    }

    @Test
    void soporteAdminYSuperUsuarioPuedenRevisar() {
        assertThat(jdbc.queryForList("""
                SELECT r.nombre FROM rol r JOIN rol_permiso rp ON rp.rol_id = r.id JOIN permiso p ON p.id = rp.permiso_id
                WHERE p.codigo = 'CANCELACION_REVISAR'
                """, String.class)).containsExactlyInAnyOrder("SOPORTE", "ADMINISTRADOR", "SUPER_USUARIO");
    }

    // --- escenario ----------------------------------------------------------------------------------

    private static void assertError(ThrowingCallable accion, HttpStatus status, CodigoError codigo) {
        assertThatThrownBy(accion).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCodigo()).isEqualTo(codigo);
        });
    }

    private long torneo() {
        long id = jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, valor_inscripcion,
                                    pct_gastos_administrativos, cierre_inscripcion_en, fecha_inicio, estado,
                                    publicado_en)
                VALUES (?, 'Torneo a revisar', 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE', 'INDIVIDUAL',
                        8, 2, 1000, 10, now() + interval '1 day', now() + interval '7 days', 'INSCRIPCION_ABIERTA',
                        now())
                RETURNING id
                """, Long.class, organizador);
        jdbc.update("UPDATE torneo SET slug = ? WHERE id = ?", "rev-" + id, id);
        return id;
    }

    private void saldo(Long usuario, String disponible) {
        jdbc.update("""
                INSERT INTO billetera (usuario_id, saldo_disponible) VALUES (?, ?)
                ON CONFLICT (usuario_id) DO UPDATE SET saldo_disponible = excluded.saldo_disponible
                """, usuario, new BigDecimal(disponible));
        entityManager.clear();
    }

    private void assertBilletera(Long usuario, String disponible, String retenido) {
        entityManager.flush();
        Map<String, Object> billetera = jdbc.queryForMap(
                "SELECT saldo_disponible, saldo_retenido FROM billetera WHERE usuario_id = ?", usuario);
        assertThat((BigDecimal) billetera.get("saldo_disponible")).isEqualByComparingTo(disponible);
        assertThat((BigDecimal) billetera.get("saldo_retenido")).isEqualByComparingTo(retenido);
    }

    private Long usuario(String nombre) {
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
        return usuarioRepository.save(usuario).getId();
    }
}
