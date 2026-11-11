package com.hydra.pica.plataforma_pica.torneo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.inscripcion.service.InscripcionService;
import com.hydra.pica.plataforma_pica.torneo.dto.CancelacionDelTorneo;
import com.hydra.pica.plataforma_pica.torneo.dto.CancelacionEnRevision;
import com.hydra.pica.plataforma_pica.torneo.dto.Comprobante;
import com.hydra.pica.plataforma_pica.torneo.dto.GastoDeclarado;
import com.hydra.pica.plataforma_pica.torneo.dto.GastoEnRevision;
import com.hydra.pica.plataforma_pica.torneo.dto.ResolucionCancelacion;
import com.hydra.pica.plataforma_pica.torneo.dto.SolicitudCancelacion;
import com.hydra.pica.plataforma_pica.torneo.event.CancelacionResuelta;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
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
@RecordApplicationEvents
class RevisionCancelacionIntegracionTest {

    private static final byte[] PDF = "%PDF-1.7\nfactura".getBytes(StandardCharsets.US_ASCII);

    @Autowired private RevisionCancelacionService revisionCancelacionService;
    @Autowired private CancelacionTorneoService cancelacionTorneoService;
    @Autowired private InscripcionService inscripcionService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private AvisoCancelacion avisoCancelacion;
    @Autowired private ApplicationEvents eventos;
    @MockitoBean private EmailService emailService;

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

    // --- lo que lee el organizador después --------------------------------------------------------

    @Test
    void elOrganizadorVeLaAprobacionConLoRetenidoYLoReintegrado() {
        revisionCancelacionService.aprobar(soporte, torneo);
        entityManager.flush();
        entityManager.clear();

        CancelacionDelTorneo detalle = cancelacionTorneoService.detalle(organizador, torneo);

        assertThat(detalle.estado()).isEqualTo("APROBADA");
        assertThat(detalle.revisadaEn()).isNotNull();
        assertThat(detalle.retenido()).isEqualByComparingTo("450.50");
        assertThat(detalle.reintegrado()).isEqualByComparingTo("2849.50");
        assertThat(detalle.gastos()).hasSize(2);
        assertThat(detalle.motivoRechazo()).isNull();
    }

    @Test
    void elOrganizadorVeElRechazoConSuMotivo() {
        revisionCancelacionService.rechazar(soporte, torneo, "Los comprobantes no son del torneo");
        entityManager.flush();
        entityManager.clear();

        CancelacionDelTorneo detalle = cancelacionTorneoService.detalle(organizador, torneo);

        assertThat(detalle.estado()).isEqualTo("RECHAZADA");
        assertThat(detalle.motivoRechazo()).isEqualTo("Los comprobantes no son del torneo");
        assertThat(detalle.retenido()).isEqualByComparingTo("0");
        assertThat(detalle.reintegrado()).isEqualByComparingTo("3300.00");
    }

    // --- la cola y los comprobantes ------------------------------------------------------------------

    @Test
    void laColaTraeSoloLasEnRevisionDeLaMasViejaALaMasNuevaConSusGastos() {
        long resuelto = enRevision("Otro en revisión");
        revisionCancelacionService.rechazar(soporte, resuelto, "No corresponde");
        long nuevo = enRevision("El más nuevo");
        long automatico = torneo();
        cancelacionTorneoService.cancelar(organizador, automatico, new SolicitudCancelacion("Lluvia", List.of()));
        entityManager.flush();
        entityManager.clear();

        Page<CancelacionEnRevision> cola = revisionCancelacionService.cola(PageRequest.of(0, 20));

        assertThat(cola.getContent()).extracting(CancelacionEnRevision::torneoId)
                .containsSubsequence(torneo, nuevo)
                .doesNotContain(resuelto, automatico);
        CancelacionEnRevision primera = cola.getContent().stream().filter(c -> c.torneoId().equals(torneo))
                .findFirst().orElseThrow();
        assertThat(primera.torneoNombre()).isEqualTo("Torneo a revisar");
        assertThat(primera.torneoSlug()).isEqualTo("rev-" + torneo);
        assertThat(primera.organizador().username()).isEqualTo("org_rev");
        assertThat(primera.motivo()).isEqualTo("Se inundó la cancha");
        assertThat(primera.pedidaEn()).isNotNull();
        assertThat(primera.pozo()).isEqualByComparingTo("3000.00");
        assertThat(primera.totalGastos()).isEqualByComparingTo("450.50");
        assertThat(primera.gastos()).extracting(GastoEnRevision::concepto, GastoEnRevision::tipoContenido)
                .containsExactly(tuple("Alquiler de la cancha", "application/pdf"), tuple("Árbitro", "application/pdf"));
    }

    @Test
    void soporteBajaElComprobanteDeUnGasto() {
        Long gastoId = gastos(torneo).get(0);

        Comprobante comprobante = revisionCancelacionService.comprobante(torneo, gastoId);

        assertThat(comprobante.contenido()).isEqualTo(PDF);
        assertThat(comprobante.tipoContenido()).isEqualTo("application/pdf");
    }

    @Test
    void elComprobanteDeUnGastoDeOtraCancelacionNoSeEncuentra() {
        long otro = enRevision("Otro");
        Long gastoDelOtro = gastos(otro).get(0);

        assertError(() -> revisionCancelacionService.comprobante(torneo, gastoDelOtro), HttpStatus.NOT_FOUND,
                CodigoError.COMPROBANTE_NO_ENCONTRADO);
        assertError(() -> revisionCancelacionService.comprobante(torneo, 999_999L), HttpStatus.NOT_FOUND,
                CodigoError.COMPROBANTE_NO_ENCONTRADO);
    }

    // --- aprobar -------------------------------------------------------------------------------------

    @Test
    void alAprobarCadaUnoPagaSuParteDeLosGastosYElOrganizadorLosCobra() {
        ResolucionCancelacion resolucion = revisionCancelacionService.aprobar(soporte, torneo);

        assertThat(resolucion.estado()).isEqualTo("APROBADA");
        assertThat(resolucion.retenido()).isEqualByComparingTo("450.50");
        assertThat(resolucion.alOrganizador()).isEqualByComparingTo("450.50");
        assertThat(resolucion.reintegrado()).isEqualByComparingTo("2849.50");
        // $450,50 × 1000 / 3000 = $150,1666…: $150,16 a cada uno y los 2 centavos que sobran, a los primeros
        assertBilletera(jugador[0], "4849.83", "0.00");
        assertBilletera(jugador[1], "4849.83", "0.00");
        assertBilletera(jugador[2], "4849.84", "0.00");
        assertBilletera(organizador, "450.50", "0.00");
        assertThat(jdbc.queryForObject("SELECT ganancia_acumulada FROM billetera WHERE usuario_id = ?",
                BigDecimal.class, organizador)).isEqualByComparingTo("450.50");
        assertThat(jdbc.queryForObject("SELECT ganancia_acumulada FROM billetera WHERE usuario_id = ?",
                BigDecimal.class, jugador[0])).isEqualByComparingTo("0");
    }

    @Test
    void loReintegradoMasLoQueCobraElOrganizadorEsLoRetenido() {
        revisionCancelacionService.aprobar(soporte, torneo);

        assertThat(jdbc.queryForObject("""
                SELECT sum(m.monto) FROM movimiento m
                WHERE m.torneo_id = ? AND (m.tipo = 'REINTEGRO' OR m.referencia_externa = ?)
                """, BigDecimal.class, torneo, "canc-t" + torneo + "-organizador")).isEqualByComparingTo("3300.00");
        assertThat(jdbc.queryForMap("""
                SELECT m.tipo, m.monto, m.torneo_id FROM movimiento m WHERE m.referencia_externa = ?
                """, "canc-t" + torneo + "-organizador")).containsEntry("tipo", "LIQUIDACION")
                .containsEntry("torneo_id", torneo);
        assertThat(jdbc.queryForObject("SELECT estado FROM custodia WHERE torneo_id = ?", String.class, torneo))
                .isEqualTo("REINTEGRADA");
    }

    @Test
    void elReintegroDiceCuantoSeRetuvo() {
        revisionCancelacionService.aprobar(soporte, torneo);

        assertThat(jdbc.queryForMap("""
                SELECT m.monto, m.descripcion FROM movimiento m JOIN billetera b ON b.id = m.billetera_id
                WHERE b.usuario_id = ? AND m.tipo = 'REINTEGRO'
                """, jugador[0])).containsEntry("monto", new BigDecimal("949.83"))
                .containsEntry("descripcion",
                        "Reintegro por la cancelación del torneo Torneo a revisar: se retuvieron $150,17 de gastos");
    }

    @Test
    void laCancelacionQuedaAprobadaYElTorneoCancelado() {
        revisionCancelacionService.aprobar(soporte, torneo);

        assertThat(jdbc.queryForMap("SELECT estado, revisor_id, motivo_rechazo FROM cancelacion WHERE torneo_id = ?",
                torneo)).containsEntry("estado", "APROBADA").containsEntry("revisor_id", soporte)
                .containsEntry("motivo_rechazo", null);
        assertThat(jdbc.queryForObject("SELECT estado FROM torneo WHERE id = ?", String.class, torneo))
                .isEqualTo("CANCELADO");
        assertThat(jdbc.queryForList("SELECT estado FROM inscripcion WHERE torneo_id = ?", String.class, torneo))
                .containsOnly("REINTEGRADA");
    }

    @Test
    void aprobarTieneLosMismosControlesQueRechazar() {
        assertError(() -> revisionCancelacionService.aprobar(organizador, torneo), HttpStatus.CONFLICT,
                CodigoError.CANCELACION_PROPIA);
        revisionCancelacionService.aprobar(soporte, torneo);
        entityManager.flush();
        entityManager.clear();

        assertError(() -> revisionCancelacionService.aprobar(soporte, torneo), HttpStatus.CONFLICT,
                CodigoError.CANCELACION_YA_RESUELTA);
        assertError(() -> revisionCancelacionService.rechazar(soporte, torneo, "Tarde"), HttpStatus.CONFLICT,
                CodigoError.CANCELACION_YA_RESUELTA);
        assertBilletera(organizador, "450.50", "0.00");
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

    // --- los mails ----------------------------------------------------------------------------------

    @Test
    void cadaResolucionPublicaElEventoDelAviso() {
        revisionCancelacionService.aprobar(soporte, torneo);
        long rechazado = enRevision("Rechazado");
        revisionCancelacionService.rechazar(soporte, rechazado, "No");
        long automatico = torneo();
        cancelacionTorneoService.cancelar(organizador, automatico, new SolicitudCancelacion("Lluvia", List.of()));

        assertThat(eventos.stream(CancelacionResuelta.class)).extracting(CancelacionResuelta::torneoId)
                .containsExactly(torneo, rechazado, automatico);
    }

    @Test
    void alAprobarCadaUnoRecibeLoQueLeDevolvieronConElDesgloseYElOrganizadorLoQueCobro() {
        revisionCancelacionService.aprobar(soporte, torneo);
        entityManager.flush();

        avisoCancelacion.avisar(new CancelacionResuelta(torneo));

        verify(emailService).enviarAviso("jug_rev_0@example.com", "Se canceló Torneo a revisar", """
                Se canceló Torneo a revisar. Motivo: Se inundó la cancha

                Soporte aprobó gastos por $450,50: Alquiler de la cancha $300, Árbitro $150,50. \
                Te tocan $150,17 de esos gastos.

                Te devolvimos $949,83 de los $1100 que pagaste (86,35 %) a tu billetera.
                """);
        verify(emailService).enviarAviso("jug_rev_2@example.com", "Se canceló Torneo a revisar", """
                Se canceló Torneo a revisar. Motivo: Se inundó la cancha

                Soporte aprobó gastos por $450,50: Alquiler de la cancha $300, Árbitro $150,50. \
                Te tocan $150,16 de esos gastos.

                Te devolvimos $949,84 de los $1100 que pagaste (86,35 %) a tu billetera.
                """);
        verify(emailService).enviarAviso("org_rev@example.com", "Soporte revisó la cancelación de Torneo a revisar",
                "Soporte aprobó los gastos de la cancelación de Torneo a revisar: te acreditamos $450,50.\n");
        verify(emailService, times(4)).enviarAviso(anyString(), anyString(), anyString());
    }

    @Test
    void alRechazarCadaUnoRecibeElCienPorCientoConElMotivo() {
        revisionCancelacionService.rechazar(soporte, torneo, "Los comprobantes no son del torneo");
        entityManager.flush();

        avisoCancelacion.avisar(new CancelacionResuelta(torneo));

        verify(emailService).enviarAviso("jug_rev_1@example.com", "Se canceló Torneo a revisar", """
                Se canceló Torneo a revisar. Motivo: Se inundó la cancha

                Soporte rechazó los gastos que declaró el organizador: Los comprobantes no son del torneo.

                Te devolvimos $1100 (100 %) a tu billetera.
                """);
        verify(emailService).enviarAviso("org_rev@example.com", "Soporte revisó la cancelación de Torneo a revisar",
                "Soporte rechazó los gastos de la cancelación de Torneo a revisar: Los comprobantes no son del torneo. "
                        + "Se devolvió todo a los participantes.\n");
    }

    @Test
    void enLaCancelacionAutomaticaSoloLesLlegaAQuienesPagaron() {
        long automatico = torneo();
        Long pago = usuario("jug_auto");
        saldo(pago, "5000.00");
        long suInscripcion = jdbc.queryForObject("""
                INSERT INTO inscripcion (torneo_id, usuario_id, estado, reglamento_aceptado_en)
                VALUES (?, ?, 'PENDIENTE_PAGO', now()) RETURNING id
                """, Long.class, automatico, pago);
        inscripcionService.pagar(pago, suInscripcion);
        cancelacionTorneoService.cancelar(organizador, automatico, new SolicitudCancelacion("Lluvia", List.of()));
        entityManager.flush();

        avisoCancelacion.avisar(new CancelacionResuelta(automatico));

        verify(emailService).enviarAviso("jug_auto@example.com", "Se canceló Torneo a revisar", """
                Se canceló Torneo a revisar. Motivo: Lluvia

                Te devolvimos $1100 (100 % de lo que pagaste) a tu billetera.
                """);
        verify(emailService, times(1)).enviarAviso(anyString(), anyString(), anyString());
    }

    @Test
    void siUnMailFallaLosDemasSalenIgual() {
        revisionCancelacionService.rechazar(soporte, torneo, "No");
        entityManager.flush();
        doThrow(new IllegalStateException("SMTP caído")).when(emailService)
                .enviarAviso(eq("jug_rev_0@example.com"), anyString(), anyString());

        avisoCancelacion.avisar(new CancelacionResuelta(torneo));

        verify(emailService, times(4)).enviarAviso(anyString(), anyString(), anyString());
    }

    // --- escenario ----------------------------------------------------------------------------------

    private static void assertError(ThrowingCallable accion, HttpStatus status, CodigoError codigo) {
        assertThatThrownBy(accion).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCodigo()).isEqualTo(codigo);
        });
    }

    /** Otro torneo del mismo organizador, cancelado con un gasto y en revisión. */
    private long enRevision(String nombre) {
        long id = torneo();
        jdbc.update("UPDATE torneo SET nombre = ? WHERE id = ?", nombre, id);
        Long jugadorDelOtro = usuario("jug_" + id);
        saldo(jugadorDelOtro, "5000.00");
        long suInscripcion = jdbc.queryForObject("""
                INSERT INTO inscripcion (torneo_id, usuario_id, estado, reglamento_aceptado_en)
                VALUES (?, ?, 'PENDIENTE_PAGO', now()) RETURNING id
                """, Long.class, id, jugadorDelOtro);
        inscripcionService.pagar(jugadorDelOtro, suInscripcion);
        cancelacionTorneoService.cancelar(organizador, id, new SolicitudCancelacion("Lluvia",
                List.of(new GastoDeclarado(new BigDecimal("100.00"), "Cancha", PDF))));
        entityManager.flush();
        entityManager.clear();
        return id;
    }

    private List<Long> gastos(long torneoId) {
        return jdbc.queryForList("""
                SELECT g.id FROM gasto_cancelacion g JOIN cancelacion c ON c.id = g.cancelacion_id
                WHERE c.torneo_id = ? ORDER BY g.id
                """, Long.class, torneoId);
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
