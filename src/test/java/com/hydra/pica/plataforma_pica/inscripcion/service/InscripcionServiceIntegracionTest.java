package com.hydra.pica.plataforma_pica.inscripcion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.control.service.BloqueoService;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionDetalle;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
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
        return jdbc.queryForObject("""
                INSERT INTO inscripcion (torneo_id, usuario_id, estado, reglamento_aceptado_en)
                VALUES (?, ?, ?, CASE WHEN ? = 'BORRADOR' THEN NULL ELSE now() END)
                RETURNING id
                """, Long.class, torneo, usuario, estado, estado);
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
