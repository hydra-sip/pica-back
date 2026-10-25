package com.hydra.pica.plataforma_pica.inscripcion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.service.CobroRechazadoException;
import com.hydra.pica.plataforma_pica.billetera.service.CobroService;
import com.hydra.pica.plataforma_pica.billetera.service.SolicitudCobro;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.control.service.BloqueoService;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionDetalle;
import com.hydra.pica.plataforma_pica.inscripcion.dto.ResumenPago;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Borrador de inscripción individual (PICA-208) contra Postgres, con V14: crear, ver y cancelar, y las
 * reglas de quién puede inscribirse. Cada test hace rollback al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class InscripcionServiceIntegracionTest {

    @Autowired private InscripcionService inscripcionService;
    @MockitoSpyBean private InscripcionRepository inscripcionRepository;
    @MockitoSpyBean private CobroService cobroService;
    @Autowired private BloqueoService bloqueoService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;

    private Long organizador;
    private Long jugador;
    private Long torneo;

    @BeforeEach
    void escenario() {
        organizador = usuario("org_insc").getId();
        jugador = usuario("jug_insc").getId();
        torneo = torneoIndividual("INSCRIPCION_ABIERTA", "now() + interval '1 day'");
    }

    // --- crear --------------------------------------------------------------

    @Test
    void creaUnBorradorDelUsuarioEnElTorneo() {
        InscripcionDetalle inscripcion = inscripcionService.crear(jugador, torneo);

        assertThat(inscripcion.id()).isNotNull();
        assertThat(inscripcion.torneoId()).isEqualTo(torneo);
        assertThat(inscripcion.usuarioId()).isEqualTo(jugador);
        assertThat(inscripcion.estado()).isEqualTo(EstadoInscripcion.BORRADOR);
        assertThat(inscripcion.reglamentoAceptadoEn()).isNull();
        assertThat(inscripcion.creadoEn()).isNotNull();
    }

    @Test
    void unTorneoInexistenteNoSeEncuentra() {
        assertError(() -> inscripcionService.crear(jugador, 999_999L), HttpStatus.NOT_FOUND, CodigoError.TORNEO_NO_ENCONTRADO);
    }

    @Test
    void unTorneoDadoDeBajaNoSeEncuentra() {
        jdbc.update("UPDATE torneo SET eliminado_en = now() WHERE id = ?", torneo);

        assertError(() -> inscripcionService.crear(jugador, torneo), HttpStatus.NOT_FOUND, CodigoError.TORNEO_NO_ENCONTRADO);
    }

    @Test
    void sinLaInscripcionAbiertaNoSePuedeCrear() {
        long cerrado = torneoIndividual("INSCRIPCION_CERRADA", "now() + interval '1 day'");

        assertError(() -> inscripcionService.crear(jugador, cerrado), HttpStatus.CONFLICT, CodigoError.INSCRIPCION_NO_ABIERTA);
    }

    @Test
    void conLaFechaDeCierrePasadaNoSePuedeCrear() {
        long vencido = torneoIndividual("INSCRIPCION_ABIERTA", "now() - interval '1 minute'");

        assertError(() -> inscripcionService.crear(jugador, vencido), HttpStatus.CONFLICT, CodigoError.INSCRIPCION_NO_ABIERTA);
    }

    @Test
    void unTorneoPorEquiposTodaviaNoSeInscribeDesdeAca() {
        long porEquipos = torneoPorEquipos();

        assertError(() -> inscripcionService.crear(jugador, porEquipos), HttpStatus.BAD_REQUEST, CodigoError.VALIDACION);
    }

    @Test
    void unTorneoInexistenteGanaSobreUnUsuarioNoActivo() {
        Usuario sinVerificar = usuario("pend2_insc");
        sinVerificar.setEstado(EstadoUsuario.PENDIENTE_VERIFICACION);
        usuarioRepository.saveAndFlush(sinVerificar);

        assertError(() -> inscripcionService.crear(sinVerificar.getId(), 999_999L), HttpStatus.NOT_FOUND, CodigoError.TORNEO_NO_ENCONTRADO);
    }

    // --- quién puede inscribirse --------------------------------------------

    @Test
    void unUsuarioQueNoEstaActivoNoSeInscribe() {
        Usuario sinVerificar = usuario("pend_insc");
        sinVerificar.setEstado(EstadoUsuario.PENDIENTE_VERIFICACION);
        usuarioRepository.saveAndFlush(sinVerificar);

        assertError(() -> inscripcionService.crear(sinVerificar.getId(), torneo), HttpStatus.CONFLICT, CodigoError.USUARIO_NO_ACTIVO);
    }

    @Test
    void unUsuarioConUnBloqueoVigenteNoSeInscribe() {
        Usuario bloqueado = usuario("bloq_insc");
        bloqueoService.bloquear(bloqueado, "Mala conducta", Instant.now().plus(5, ChronoUnit.DAYS));

        assertError(() -> inscripcionService.crear(bloqueado.getId(), torneo), HttpStatus.FORBIDDEN, CodigoError.USUARIO_BLOQUEADO);
    }

    @Test
    void elOrganizadorNoSeInscribeEnSuTorneo() {
        assertError(() -> inscripcionService.crear(organizador, torneo), HttpStatus.FORBIDDEN, CodigoError.INSCRIPCION_NO_PERMITIDA_EN_TORNEO);
    }

    @Test
    void unArbitroDesignadoNoSeInscribeEnElTorneo() {
        jdbc.update("INSERT INTO torneo_arbitro (torneo_id, usuario_id) VALUES (?, ?)", torneo, jugador);

        assertError(() -> inscripcionService.crear(jugador, torneo), HttpStatus.FORBIDDEN, CodigoError.INSCRIPCION_NO_PERMITIDA_EN_TORNEO);
    }

    @Test
    void conUnaInscripcionActivaNoSeInscribeDeNuevo() {
        inscripcionDe(jugador, "PENDIENTE_PAGO");

        assertError(() -> inscripcionService.crear(jugador, torneo), HttpStatus.CONFLICT, CodigoError.INSCRIPCION_DUPLICADA);
    }

    @Test
    void conUnaConfirmadaTampocoSeInscribeDeNuevo() {
        inscripcionDe(jugador, "CONFIRMADA");

        assertError(() -> inscripcionService.crear(jugador, torneo), HttpStatus.CONFLICT, CodigoError.INSCRIPCION_DUPLICADA);
    }

    @Test
    void unBorradorNoImpideCrearOtro() {
        inscripcionDe(jugador, "BORRADOR");

        assertThat(inscripcionService.crear(jugador, torneo).estado()).isEqualTo(EstadoInscripcion.BORRADOR);
    }

    @Test
    void unaCanceladaNoImpideInscribirseDeNuevo() {
        inscripcionDe(jugador, "CANCELADA");

        assertThat(inscripcionService.crear(jugador, torneo).estado()).isEqualTo(EstadoInscripcion.BORRADOR);
    }

    // --- ver ----------------------------------------------------------------

    @Test
    void elParticipanteVeSuInscripcion() {
        long id = inscripcionDe(jugador, "BORRADOR");

        InscripcionDetalle vista = inscripcionService.detalle(jugador, id);

        assertThat(vista.id()).isEqualTo(id);
        assertThat(vista.usuarioId()).isEqualTo(jugador);
        assertThat(vista.estado()).isEqualTo(EstadoInscripcion.BORRADOR);
    }

    @Test
    void elOrganizadorDelTorneoVeLaInscripcion() {
        long id = inscripcionDe(jugador, "BORRADOR");

        assertThat(inscripcionService.detalle(organizador, id).id()).isEqualTo(id);
    }

    @Test
    void otroUsuarioNoVeLaInscripcion() {
        long id = inscripcionDe(jugador, "BORRADOR");
        Long otro = usuario("otro_insc").getId();

        assertError(() -> inscripcionService.detalle(otro, id), HttpStatus.NOT_FOUND, CodigoError.INSCRIPCION_NO_ENCONTRADA);
    }

    @Test
    void unaInscripcionInexistenteNoSeEncuentra() {
        assertError(() -> inscripcionService.detalle(jugador, 999_999L), HttpStatus.NOT_FOUND, CodigoError.INSCRIPCION_NO_ENCONTRADA);
    }

    // --- cancelar -----------------------------------------------------------

    @Test
    void cancelaUnBorrador() {
        long id = inscripcionDe(jugador, "BORRADOR");

        inscripcionService.cancelar(jugador, id);

        assertThat(inscripcionService.detalle(jugador, id).estado()).isEqualTo(EstadoInscripcion.CANCELADA);
    }

    @Test
    void cancelaUnaPendienteDePago() {
        long id = inscripcionDe(jugador, "PENDIENTE_PAGO");

        inscripcionService.cancelar(jugador, id);

        assertThat(inscripcionService.detalle(jugador, id).estado()).isEqualTo(EstadoInscripcion.CANCELADA);
    }

    @Test
    void unaConfirmadaNoSeCancelaDesdeAca() {
        long id = inscripcionDe(jugador, "CONFIRMADA");

        assertError(() -> inscripcionService.cancelar(jugador, id), HttpStatus.CONFLICT, CodigoError.INSCRIPCION_NO_EDITABLE);
    }

    @Test
    void unaYaCanceladaNoSeCancelaDeNuevo() {
        long id = inscripcionDe(jugador, "CANCELADA");

        assertError(() -> inscripcionService.cancelar(jugador, id), HttpStatus.CONFLICT, CodigoError.INSCRIPCION_NO_EDITABLE);
    }

    @Test
    void soloElParticipanteCancelaSuInscripcion() {
        long id = inscripcionDe(jugador, "BORRADOR");

        assertError(() -> inscripcionService.cancelar(organizador, id), HttpStatus.NOT_FOUND, CodigoError.INSCRIPCION_NO_ENCONTRADA);
    }

    // --- confirmar ----------------------------------------------------------

    @Test
    void confirmarPasaElBorradorAPendienteDePagoYGuardaCuandoSeAceptoElReglamento() {
        long id = inscripcionDe(jugador, "BORRADOR");
        Instant antes = Instant.now();

        InscripcionDetalle confirmada = inscripcionService.confirmar(jugador, id, true);

        assertThat(confirmada.estado()).isEqualTo(EstadoInscripcion.PENDIENTE_PAGO);
        assertThat(confirmada.reglamentoAceptadoEn()).isAfterOrEqualTo(antes.minusSeconds(1));
        assertThat(inscripcionService.detalle(jugador, id).estado()).isEqualTo(EstadoInscripcion.PENDIENTE_PAGO);
    }

    @Test
    void sinAceptarElReglamentoNoSeConfirmaYSigueEnBorrador() {
        long id = inscripcionDe(jugador, "BORRADOR");

        assertError(() -> inscripcionService.confirmar(jugador, id, false), HttpStatus.CONFLICT, CodigoError.REGLAMENTO_NO_ACEPTADO);

        InscripcionDetalle vista = inscripcionService.detalle(jugador, id);
        assertThat(vista.estado()).isEqualTo(EstadoInscripcion.BORRADOR);
        assertThat(vista.reglamentoAceptadoEn()).isNull();
    }

    @Test
    void soloSeConfirmaUnBorrador() {
        for (String estado : new String[] {"PENDIENTE_PAGO", "CONFIRMADA", "CANCELADA"}) {
            long id = inscripcionDe(usuario("u_" + estado.toLowerCase()).getId(), estado);
            Long dueno = jdbc.queryForObject("SELECT usuario_id FROM inscripcion WHERE id = ?", Long.class, id);

            assertError(() -> inscripcionService.confirmar(dueno, id, true), HttpStatus.CONFLICT, CodigoError.INSCRIPCION_NO_EDITABLE);
        }
    }

    @Test
    void soloElParticipanteConfirmaSuInscripcion() {
        long id = inscripcionDe(jugador, "BORRADOR");

        assertError(() -> inscripcionService.confirmar(organizador, id, true), HttpStatus.NOT_FOUND, CodigoError.INSCRIPCION_NO_ENCONTRADA);
        assertError(() -> inscripcionService.confirmar(usuario("otro_conf").getId(), id, true), HttpStatus.NOT_FOUND, CodigoError.INSCRIPCION_NO_ENCONTRADA);
        assertThat(inscripcionService.detalle(jugador, id).estado()).isEqualTo(EstadoInscripcion.BORRADOR);
    }

    @Test
    void alConfirmarElTorneoTieneQueSeguirAbierto() {
        long id = inscripcionDe(jugador, "BORRADOR");
        jdbc.update("UPDATE torneo SET estado = 'INSCRIPCION_CERRADA' WHERE id = ?", torneo);

        assertError(() -> inscripcionService.confirmar(jugador, id, true), HttpStatus.CONFLICT, CodigoError.INSCRIPCION_NO_ABIERTA);
    }

    @Test
    void alConfirmarLaFechaDeCierreNoPuedePasar() {
        long id = inscripcionDe(jugador, "BORRADOR");
        jdbc.update("UPDATE torneo SET cierre_inscripcion_en = now() - interval '1 minute' WHERE id = ?", torneo);

        assertError(() -> inscripcionService.confirmar(jugador, id, true), HttpStatus.CONFLICT, CodigoError.INSCRIPCION_NO_ABIERTA);
    }

    @Test
    void unUsuarioBloqueadoDespuesDelBorradorNoConfirma() {
        Usuario bloqueado = usuario("bloq_conf");
        long id = inscripcionDe(bloqueado.getId(), "BORRADOR");
        bloqueoService.bloquear(bloqueado, "Mala conducta", Instant.now().plus(5, ChronoUnit.DAYS));

        assertError(() -> inscripcionService.confirmar(bloqueado.getId(), id, true), HttpStatus.FORBIDDEN, CodigoError.USUARIO_BLOQUEADO);
    }

    @Test
    void unUsuarioQueDejoDeEstarActivoNoConfirma() {
        Usuario suspendido = usuario("susp_conf");
        long id = inscripcionDe(suspendido.getId(), "BORRADOR");
        suspendido.setEstado(EstadoUsuario.PENDIENTE_VERIFICACION);
        usuarioRepository.saveAndFlush(suspendido);

        assertError(() -> inscripcionService.confirmar(suspendido.getId(), id, true), HttpStatus.CONFLICT, CodigoError.USUARIO_NO_ACTIVO);
    }

    @Test
    void conOtraInscripcionActivaEnElTorneoNoSeConfirma() {
        long borrador = inscripcionDe(jugador, "BORRADOR");
        inscripcionDe(jugador, "PENDIENTE_PAGO");

        assertError(() -> inscripcionService.confirmar(jugador, borrador, true), HttpStatus.CONFLICT, CodigoError.INSCRIPCION_DUPLICADA);
        assertThat(inscripcionService.detalle(jugador, borrador).estado()).isEqualTo(EstadoInscripcion.BORRADOR);
    }

    @Test
    void conElCupoCompletoNoSeConfirmaYSigueEnBorrador() {
        long id = inscripcionDe(jugador, "BORRADOR");
        llenarElCupo(2);

        assertError(() -> inscripcionService.confirmar(jugador, id, true), HttpStatus.CONFLICT, CodigoError.CUPO_COMPLETO);

        InscripcionDetalle vista = inscripcionService.detalle(jugador, id);
        assertThat(vista.estado()).isEqualTo(EstadoInscripcion.BORRADOR);
        assertThat(vista.reglamentoAceptadoEn()).isNull();
    }

    @Test
    void lasPendientesDePagoNoOcupanCupo() {
        long id = inscripcionDe(jugador, "BORRADOR");
        jdbc.update("UPDATE torneo SET cupo_maximo = 2 WHERE id = ?", torneo);
        inscripcionDe(usuario("conf_cupo").getId(), "CONFIRMADA");
        inscripcionDe(usuario("pend_cupo").getId(), "PENDIENTE_PAGO");

        assertThat(inscripcionService.confirmar(jugador, id, true).estado()).isEqualTo(EstadoInscripcion.PENDIENTE_PAGO);
    }

    @Test
    void enUnTorneoGratisLaInscripcionQuedaConfirmadaSinPasarPorElPago() {
        long gratis = torneoIndividualGratis();
        long id = inscripcionEn(gratis, jugador, "BORRADOR");

        InscripcionDetalle confirmada = inscripcionService.confirmar(jugador, id, true);

        assertThat(confirmada.estado()).isEqualTo(EstadoInscripcion.CONFIRMADA);
        assertThat(confirmada.reglamentoAceptadoEn()).isNotNull();
    }

    @Test
    void enUnTorneoGratisConElCupoCompletoTampocoSeConfirma() {
        long gratis = torneoIndividualGratis();
        jdbc.update("UPDATE torneo SET cupo_maximo = 2 WHERE id = ?", gratis);
        inscripcionEn(gratis, usuario("g1_cupo").getId(), "CONFIRMADA");
        inscripcionEn(gratis, usuario("g2_cupo").getId(), "CONFIRMADA");
        long id = inscripcionEn(gratis, jugador, "BORRADOR");

        assertError(() -> inscripcionService.confirmar(jugador, id, true), HttpStatus.CONFLICT, CodigoError.CUPO_COMPLETO);
        assertThat(inscripcionService.detalle(jugador, id).estado()).isEqualTo(EstadoInscripcion.BORRADOR);
    }

    @Test
    void unTorneoDadoDeBajaNoSeConfirma() {
        long id = inscripcionDe(jugador, "BORRADOR");
        jdbc.update("UPDATE torneo SET eliminado_en = now() WHERE id = ?", torneo);

        assertError(() -> inscripcionService.confirmar(jugador, id, true), HttpStatus.NOT_FOUND, CodigoError.TORNEO_NO_ENCONTRADO);
    }

    @Test
    void unTorneoGratisDadoDeBajaTampocoSeConfirma() {
        long gratis = torneoIndividualGratis();
        long id = inscripcionEn(gratis, jugador, "BORRADOR");
        jdbc.update("UPDATE torneo SET eliminado_en = now() WHERE id = ?", gratis);

        assertError(() -> inscripcionService.confirmar(jugador, id, true), HttpStatus.NOT_FOUND, CodigoError.TORNEO_NO_ENCONTRADO);
    }

    @Test
    void siLaBaseDetectaLaDuplicadaDespuesDelChequeoEsUn409Duplicada() {
        long borrador = inscripcionDe(jugador, "BORRADOR");
        inscripcionDe(jugador, "PENDIENTE_PAGO");
        // otra confirmación concurrente ganó la carrera después de que este chequeo pasó
        doReturn(false).when(inscripcionRepository).existsByTorneoIdAndUsuarioIdAndEstadoIn(anyLong(), anyLong(), any());

        assertError(() -> inscripcionService.confirmar(jugador, borrador, true), HttpStatus.CONFLICT, CodigoError.INSCRIPCION_DUPLICADA);
    }

    // --- pagar --------------------------------------------------------------

    @Test
    void pagarConfirmaLaInscripcionYRegistraElCobroDelValorDelTorneo() {
        long id = inscripcionDe(jugador, "PENDIENTE_PAGO");

        InscripcionDetalle pagada = inscripcionService.pagar(jugador, id);

        assertThat(pagada.estado()).isEqualTo(EstadoInscripcion.CONFIRMADA);
        assertThat(inscripcionService.detalle(jugador, id).estado()).isEqualTo(EstadoInscripcion.CONFIRMADA);
        Map<String, Object> cobro = jdbc.queryForMap("""
                SELECT m.tipo, m.estado, m.monto, b.usuario_id
                FROM movimiento m JOIN billetera b ON b.id = m.billetera_id
                WHERE m.inscripcion_id = ?
                """, id);
        assertThat(cobro).containsEntry("tipo", "INSCRIPCION").containsEntry("estado", "CONFIRMADO")
                .containsEntry("usuario_id", jugador);
        assertThat((BigDecimal) cobro.get("monto")).isEqualByComparingTo("1000");
    }

    @Test
    void reintentarElPagoDeUnaConfirmadaRespondeConExitoYNoCobraDeNuevo() {
        long id = inscripcionDe(jugador, "PENDIENTE_PAGO");
        inscripcionService.pagar(jugador, id);

        InscripcionDetalle reintento = inscripcionService.pagar(jugador, id);

        assertThat(reintento.estado()).isEqualTo(EstadoInscripcion.CONFIRMADA);
        assertThat(movimientosDe(id)).isEqualTo(1);
    }

    @Test
    void reintentarUnaConfirmadaSigueSiendoExitoAunqueElTorneoYaCerro() {
        long id = inscripcionDe(jugador, "CONFIRMADA");
        jdbc.update("UPDATE torneo SET estado = 'INSCRIPCION_CERRADA' WHERE id = ?", torneo);

        assertThat(inscripcionService.pagar(jugador, id).estado()).isEqualTo(EstadoInscripcion.CONFIRMADA);
        assertThat(movimientosDe(id)).isZero();
    }

    @Test
    void elCobroLlevaLaClaveDeLaInscripcionElMontoDelTorneoYElTipoInscripcion() {
        long id = inscripcionDe(jugador, "PENDIENTE_PAGO");

        inscripcionService.pagar(jugador, id);

        ArgumentCaptor<SolicitudCobro> solicitud = ArgumentCaptor.forClass(SolicitudCobro.class);
        verify(cobroService).cobrar(solicitud.capture());
        assertThat(solicitud.getValue().claveIdempotencia()).isEqualTo("inscripcion-" + id);
        assertThat(solicitud.getValue().tipo()).isEqualTo(TipoMovimiento.INSCRIPCION);
        assertThat(solicitud.getValue().monto()).isEqualByComparingTo("1000");
        assertThat(solicitud.getValue().usuarioId()).isEqualTo(jugador);
        assertThat(solicitud.getValue().torneoId()).isEqualTo(torneo);
        assertThat(solicitud.getValue().inscripcionId()).isEqualTo(id);
    }

    @Test
    void reintentarUnaConfirmadaSigueSiendoExitoAunqueElTorneoSeHayaDadoDeBaja() {
        long id = inscripcionDe(jugador, "CONFIRMADA");
        jdbc.update("UPDATE torneo SET eliminado_en = now() WHERE id = ?", torneo);

        assertThat(inscripcionService.pagar(jugador, id).estado()).isEqualTo(EstadoInscripcion.CONFIRMADA);
        verify(cobroService, never()).cobrar(any());
    }

    @Test
    void soloSePagaUnaPendienteDePago() {
        for (String estado : new String[] {"BORRADOR", "CANCELADA"}) {
            Long dueno = usuario("pagar_" + estado.toLowerCase()).getId();
            long id = inscripcionDe(dueno, estado);

            assertError(() -> inscripcionService.pagar(dueno, id), HttpStatus.CONFLICT, CodigoError.INSCRIPCION_NO_EDITABLE);
            assertThat(movimientosDe(id)).isZero();
        }
    }

    @Test
    void soloElParticipantePagaSuInscripcion() {
        long id = inscripcionDe(jugador, "PENDIENTE_PAGO");

        assertError(() -> inscripcionService.pagar(organizador, id), HttpStatus.NOT_FOUND, CodigoError.INSCRIPCION_NO_ENCONTRADA);
        assertError(() -> inscripcionService.pagar(usuario("otro_pago").getId(), id), HttpStatus.NOT_FOUND, CodigoError.INSCRIPCION_NO_ENCONTRADA);
        assertError(() -> inscripcionService.pagar(jugador, 999_999L), HttpStatus.NOT_FOUND, CodigoError.INSCRIPCION_NO_ENCONTRADA);
        assertThat(movimientosDe(id)).isZero();
    }

    @Test
    void alPagarElTorneoTieneQueSeguirConLaInscripcionAbierta() {
        long id = inscripcionDe(jugador, "PENDIENTE_PAGO");
        jdbc.update("UPDATE torneo SET estado = 'INSCRIPCION_CERRADA' WHERE id = ?", torneo);

        assertError(() -> inscripcionService.pagar(jugador, id), HttpStatus.CONFLICT, CodigoError.INSCRIPCION_NO_ABIERTA);
        assertThat(movimientosDe(id)).isZero();
        assertThat(inscripcionService.detalle(jugador, id).estado()).isEqualTo(EstadoInscripcion.PENDIENTE_PAGO);
    }

    @Test
    void alPagarLaFechaDeCierreNoPuedePasar() {
        long id = inscripcionDe(jugador, "PENDIENTE_PAGO");
        jdbc.update("UPDATE torneo SET cierre_inscripcion_en = now() - interval '1 minute' WHERE id = ?", torneo);

        assertError(() -> inscripcionService.pagar(jugador, id), HttpStatus.CONFLICT, CodigoError.INSCRIPCION_NO_ABIERTA);
        assertThat(movimientosDe(id)).isZero();
    }

    @Test
    void unTorneoDadoDeBajaNoSePaga() {
        long id = inscripcionDe(jugador, "PENDIENTE_PAGO");
        jdbc.update("UPDATE torneo SET eliminado_en = now() WHERE id = ?", torneo);

        assertError(() -> inscripcionService.pagar(jugador, id), HttpStatus.NOT_FOUND, CodigoError.TORNEO_NO_ENCONTRADO);
        assertThat(movimientosDe(id)).isZero();
    }

    @Test
    void unUsuarioBloqueadoDespuesDeConfirmarNoPagaNiSeLeCobra() {
        Usuario bloqueado = usuario("bloq_pago");
        long id = inscripcionDe(bloqueado.getId(), "PENDIENTE_PAGO");
        bloqueoService.bloquear(bloqueado, "Mala conducta", Instant.now().plus(5, ChronoUnit.DAYS));

        assertError(() -> inscripcionService.pagar(bloqueado.getId(), id), HttpStatus.FORBIDDEN, CodigoError.USUARIO_BLOQUEADO);
        assertThat(movimientosDe(id)).isZero();
    }

    @Test
    void unUsuarioQueDejoDeEstarActivoNoPaga() {
        Usuario inactivo = usuario("inact_pago");
        long id = inscripcionDe(inactivo.getId(), "PENDIENTE_PAGO");
        inactivo.setEstado(EstadoUsuario.PENDIENTE_VERIFICACION);
        usuarioRepository.saveAndFlush(inactivo);

        assertError(() -> inscripcionService.pagar(inactivo.getId(), id), HttpStatus.CONFLICT, CodigoError.USUARIO_NO_ACTIVO);
        assertThat(movimientosDe(id)).isZero();
    }

    @Test
    void conElCupoCompletoElPagoSeRechazaAntesDeCobrar() {
        long id = inscripcionDe(jugador, "PENDIENTE_PAGO");
        llenarElCupo(2);

        assertError(() -> inscripcionService.pagar(jugador, id), HttpStatus.CONFLICT, CodigoError.CUPO_COMPLETO);

        verify(cobroService, never()).cobrar(any());
        assertThat(movimientosDe(id)).isZero();
        assertThat(inscripcionService.detalle(jugador, id).estado()).isEqualTo(EstadoInscripcion.PENDIENTE_PAGO);
    }

    @Test
    void lasPendientesDePagoNoOcupanCupoAlPagar() {
        long id = inscripcionDe(jugador, "PENDIENTE_PAGO");
        jdbc.update("UPDATE torneo SET cupo_maximo = 2 WHERE id = ?", torneo);
        inscripcionDe(usuario("conf_pago").getId(), "CONFIRMADA");
        inscripcionDe(usuario("pend_pago").getId(), "PENDIENTE_PAGO");

        assertThat(inscripcionService.pagar(jugador, id).estado()).isEqualTo(EstadoInscripcion.CONFIRMADA);
    }

    @Test
    void siElCobroSeRechazaLaInscripcionSigueEnPendienteDePago() {
        long id = inscripcionDe(jugador, "PENDIENTE_PAGO");
        doThrow(new CobroRechazadoException("sin fondos")).when(cobroService).cobrar(any());

        assertError(() -> inscripcionService.pagar(jugador, id), HttpStatus.CONFLICT, CodigoError.COBRO_RECHAZADO);

        assertThat(inscripcionService.detalle(jugador, id).estado()).isEqualTo(EstadoInscripcion.PENDIENTE_PAGO);
        assertThat(movimientosDe(id)).isZero();
    }

    @Test
    void enUnTorneoSinCostoPagarConfirmaSinCobrar() {
        long gratis = torneoIndividualGratis();
        long id = inscripcionEn(gratis, jugador, "PENDIENTE_PAGO");

        assertThat(inscripcionService.pagar(jugador, id).estado()).isEqualTo(EstadoInscripcion.CONFIRMADA);

        verify(cobroService, never()).cobrar(any());
    }

    @Test
    void enUnTorneoSinCostoConElCupoCompletoTampocoSePaga() {
        long gratis = torneoIndividualGratis();
        jdbc.update("UPDATE torneo SET cupo_maximo = 2 WHERE id = ?", gratis);
        inscripcionEn(gratis, usuario("g1_pago").getId(), "CONFIRMADA");
        inscripcionEn(gratis, usuario("g2_pago").getId(), "CONFIRMADA");
        long id = inscripcionEn(gratis, jugador, "PENDIENTE_PAGO");

        assertError(() -> inscripcionService.pagar(jugador, id), HttpStatus.CONFLICT, CodigoError.CUPO_COMPLETO);
    }

    // --- resumen de pago ----------------------------------------------------

    @Test
    void elResumenMuestraElTorneoElParticipanteElMontoLaMonedaElCupoQueQuedaYElCierre() {
        long id = inscripcionDe(jugador, "PENDIENTE_PAGO");
        inscripcionDe(usuario("conf1_res").getId(), "CONFIRMADA");
        inscripcionDe(usuario("conf2_res").getId(), "CONFIRMADA");
        inscripcionDe(usuario("pend_res").getId(), "PENDIENTE_PAGO");

        ResumenPago resumen = inscripcionService.resumenPago(jugador, id);

        assertThat(resumen.inscripcionId()).isEqualTo(id);
        assertThat(resumen.torneo().id()).isEqualTo(torneo);
        assertThat(resumen.torneo().nombre()).isEqualTo("Torneo");
        assertThat(resumen.participante().id()).isEqualTo(jugador);
        assertThat(resumen.participante().username()).isEqualTo("jug_insc");
        assertThat(resumen.participante().nombres()).isEqualTo("jug_insc");
        assertThat(resumen.monto()).isEqualByComparingTo("1000");
        assertThat(resumen.moneda()).isEqualTo("ARS");
        assertThat(resumen.cupoMaximo()).isEqualTo(8);
        assertThat(resumen.cupoDisponible()).isEqualTo(6);
        assertThat(resumen.cierreInscripcionEn()).isAfter(Instant.now());
    }

    @Test
    void elResumenSoloSeVeEnPendienteDePago() {
        for (String estado : new String[] {"BORRADOR", "CONFIRMADA", "CANCELADA"}) {
            Long dueno = usuario("res_" + estado.toLowerCase()).getId();
            long id = inscripcionDe(dueno, estado);

            assertError(() -> inscripcionService.resumenPago(dueno, id), HttpStatus.CONFLICT, CodigoError.INSCRIPCION_NO_EDITABLE);
        }
    }

    @Test
    void elResumenSoloLoVeElParticipante() {
        long id = inscripcionDe(jugador, "PENDIENTE_PAGO");

        assertError(() -> inscripcionService.resumenPago(organizador, id), HttpStatus.NOT_FOUND, CodigoError.INSCRIPCION_NO_ENCONTRADA);
        assertError(() -> inscripcionService.resumenPago(usuario("otro_res").getId(), id), HttpStatus.NOT_FOUND, CodigoError.INSCRIPCION_NO_ENCONTRADA);
        assertError(() -> inscripcionService.resumenPago(jugador, 999_999L), HttpStatus.NOT_FOUND, CodigoError.INSCRIPCION_NO_ENCONTRADA);
    }

    // --- reglas de la base --------------------------------------------------

    @Test
    void fueraDeBorradorLaBaseExigeElReglamentoAceptado() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO inscripcion (torneo_id, usuario_id, estado) VALUES (?, ?, 'PENDIENTE_PAGO')", torneo, jugador))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_inscripcion_reglamento");
    }

    @Test
    void laBaseNoAceptaDosInscripcionesActivasDelMismoUsuario() {
        inscripcionDe(jugador, "PENDIENTE_PAGO");

        assertThatThrownBy(() -> inscripcionDe(jugador, "CONFIRMADA"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_inscripcion_activa_torneo_usuario");
    }

    // --- escenario ----------------------------------------------------------

    private void assertError(ThrowingCallable accion, HttpStatus status, CodigoError codigo) {
        ApiException error = catchThrowableOfType(ApiException.class, accion);
        assertThat(error).isNotNull();
        assertThat(error.getStatus()).isEqualTo(status);
        assertThat(error.getCodigo()).isEqualTo(codigo);
    }

    private long inscripcionDe(long usuario, String estado) {
        return inscripcionEn(torneo, usuario, estado);
    }

    private void llenarElCupo(int lugares) {
        jdbc.update("UPDATE torneo SET cupo_maximo = ? WHERE id = ?", lugares, torneo);
        for (int i = 0; i < lugares; i++) {
            inscripcionDe(usuario("lleno_" + i).getId(), "CONFIRMADA");
        }
    }

    private long inscripcionEn(long torneoId, long usuario, String estado) {
        return jdbc.queryForObject("""
                INSERT INTO inscripcion (torneo_id, usuario_id, estado, reglamento_aceptado_en)
                VALUES (?, ?, ?, CASE WHEN ? = 'BORRADOR' THEN NULL ELSE now() END)
                RETURNING id
                """, Long.class, torneoId, usuario, estado, estado);
    }

    private int movimientosDe(long inscripcionId) {
        return jdbc.queryForObject("SELECT count(*) FROM movimiento WHERE inscripcion_id = ?", Integer.class, inscripcionId);
    }

    private long torneoPorEquipos() {
        return jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, tamanio_equipo, valor_inscripcion,
                                    pct_pozo_competidores, pct_premio_rifa, cierre_inscripcion_en, fecha_inicio, estado)
                VALUES (?, 'Torneo', 'https://r', 'Futbol', 'ELIMINACION_DIRECTA', 'PRESENCIAL', 'EQUIPO', 8, 2, 5,
                        1000, 50, 10, now() + interval '1 day', now() + interval '7 days', 'INSCRIPCION_ABIERTA')
                RETURNING id
                """, Long.class, organizador);
    }

    private long torneoIndividualGratis() {
        long gratis = torneoIndividual("INSCRIPCION_ABIERTA", "now() + interval '1 day'");
        jdbc.update("UPDATE torneo SET valor_inscripcion = 0 WHERE id = ?", gratis);
        return gratis;
    }

    private long torneoIndividual(String estado, String cierre) {
        return jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, valor_inscripcion,
                                    pct_pozo_competidores, pct_premio_rifa, cierre_inscripcion_en, fecha_inicio, estado)
                VALUES (?, 'Torneo', 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE', 'INDIVIDUAL', 8, 2,
                        1000, 50, 10, %s, now() + interval '7 days', ?)
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
