package com.hydra.pica.plataforma_pica.inscripcion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.billetera.service.CobroRechazadoException;
import com.hydra.pica.plataforma_pica.billetera.service.CobroService;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.control.service.BloqueoService;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionDetalle;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionEquipoRequest;
import com.hydra.pica.plataforma_pica.inscripcion.dto.ReemplazoDetalle;
import com.hydra.pica.plataforma_pica.inscripcion.dto.ReemplazoRequest;
import com.hydra.pica.plataforma_pica.inscripcion.dto.ResumenPago;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioPublico;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Borrador de inscripción de equipo con su nómina (PICA-208) contra Postgres, con V16: las reglas que pone
 * la base (el máximo de nómina del torneo y las FK compuestas de la nómina) y el servicio. Cada test hace
 * rollback al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class InscripcionEquipoServiceIntegracionTest {

    @Autowired private InscripcionService inscripcionService;
    @MockitoSpyBean private InscripcionRepository inscripcionRepository;
    @MockitoSpyBean private CobroService cobroService;
    @Autowired private BloqueoService bloqueoService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;

    private Long organizador;
    private Long capitan;
    private Long equipo;
    private Long torneo;

    @BeforeEach
    void escenario() {
        organizador = usuario("org_eq").getId();
        capitan = usuario("cap_eq").getId();
        equipo = equipo("Los Halcones", capitan);
        torneo = torneoPorEquipos(5, 8);
    }

    // --- reglas de la base --------------------------------------------------

    @Test
    void unTorneoPorEquiposExigeElMaximoDeNomina() {
        assertThatThrownBy(() -> torneoPorEquipos(5, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_torneo_nomina_maxima");
    }

    @Test
    void elMaximoDeNominaNoPuedeSerMenorQueElTamanioDeEquipo() {
        assertThatThrownBy(() -> torneoPorEquipos(5, 4))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_torneo_nomina_maxima");
    }

    @Test
    void elMaximoDeNominaPuedeSerIgualAlTamanioDeEquipo() {
        assertThat(torneoPorEquipos(5, 5)).isNotNull();
    }

    @Test
    void unTorneoIndividualNoTieneMaximoDeNomina() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, nomina_maxima, valor_inscripcion,
                                    pct_pozo_competidores, pct_premio_rifa, cierre_inscripcion_en, fecha_inicio, estado)
                VALUES (?, 'Torneo', 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE', 'INDIVIDUAL', 8, 2, 8,
                        1000, 50, 10, now() + interval '1 day', now() + interval '7 days', 'INSCRIPCION_ABIERTA')
                """, organizador))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_torneo_nomina_maxima");
    }

    @Test
    void laNominaSoloAceptaIntegrantesDelEquipo() {
        long inscripcion = inscripcionDeEquipo(torneo, equipo, "BORRADOR");
        Long ajeno = usuario("ajeno_eq").getId();

        assertThatThrownBy(() -> enNomina(inscripcion, ajeno, equipo))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_nomina_integrante");
    }

    @Test
    void laNominaSoloAceptaElEquipoDeLaInscripcion() {
        long inscripcion = inscripcionDeEquipo(torneo, equipo, "BORRADOR");
        Long otroCapitan = usuario("cap2_eq").getId();
        Long otroEquipo = equipo("Los Gavilanes", otroCapitan);

        // el integrante es del otro equipo, pero la inscripción es de Los Halcones
        assertThatThrownBy(() -> enNomina(inscripcion, otroCapitan, otroEquipo))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_nomina_inscripcion");
    }

    @Test
    void darDeBajaAUnIntegranteNoLoSacaDeLaNominaYaInscripta() {
        Long jugador = usuario("jug_eq").getId();
        integrante(equipo, jugador);
        long inscripcion = inscripcionDeEquipo(torneo, equipo, "BORRADOR");
        enNomina(inscripcion, capitan, equipo);
        enNomina(inscripcion, jugador, equipo);

        jdbc.update("UPDATE usuario_equipo SET eliminado_en = now() WHERE usuario_id = ? AND equipo_id = ?", jugador, equipo);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM nomina WHERE inscripcion_id = ?", Integer.class, inscripcion))
                .isEqualTo(2);
    }

    // --- crear --------------------------------------------------------------

    @Test
    void creaElBorradorDeUnEquipoExistenteConElCapitanEnLaNomina() {
        InscripcionDetalle borrador = inscripcionService.crear(capitan, torneo, existente(equipo));

        assertThat(borrador.id()).isNotNull();
        assertThat(borrador.estado()).isEqualTo(EstadoInscripcion.BORRADOR);
        assertThat(borrador.torneoId()).isEqualTo(torneo);
        assertThat(borrador.usuarioId()).isNull();
        assertThat(borrador.equipo().id()).isEqualTo(equipo);
        assertThat(borrador.equipo().nombre()).isEqualTo("Los Halcones");
        assertThat(borrador.nomina()).extracting(UsuarioPublico::id).containsExactly(capitan);
        assertThat(borrador.reglamentoAceptadoEn()).isNull();
    }

    @Test
    void conUnNombreNuevoCreaElEquipoConElCapitanComoCapitanEIntegrante() {
        InscripcionDetalle borrador = inscripcionService.crear(capitan, torneo, nuevo("Los Zorros"));

        Map<String, Object> creado = jdbc.queryForMap("SELECT id, capitan_id, eliminado_en FROM equipo WHERE nombre = 'Los Zorros'");
        assertThat(creado).containsEntry("capitan_id", capitan);
        assertThat(creado.get("eliminado_en")).isNull();
        assertThat(borrador.equipo().id()).isEqualTo(creado.get("id"));
        assertThat(borrador.equipo().nombre()).isEqualTo("Los Zorros");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usuario_equipo WHERE equipo_id = ? AND usuario_id = ? AND eliminado_en IS NULL",
                Integer.class, creado.get("id"), capitan)).isEqualTo(1);
        assertThat(borrador.nomina()).extracting(UsuarioPublico::id).containsExactly(capitan);
    }

    @Test
    void elNombreDelEquipoNuevoNoPuedeEstarEnBlanco() {
        assertError(() -> inscripcionService.crear(capitan, torneo, nuevo("   ")), HttpStatus.BAD_REQUEST, CodigoError.VALIDACION);
    }

    // --- nómina -------------------------------------------------------------

    @Test
    void laNominaTieneAlCapitanYALosJugadoresCargados() {
        Long uno = integranteNuevo("uno_eq");
        Long dos = integranteNuevo("dos_eq");

        InscripcionDetalle borrador = inscripcionService.crear(capitan, torneo, existente(equipo, uno, dos));

        assertThat(borrador.nomina()).extracting(UsuarioPublico::id).containsExactlyInAnyOrder(capitan, uno, dos);
        assertThat(borrador.nomina()).extracting(UsuarioPublico::username).contains("uno_eq", "dos_eq", "cap_eq");
    }

    @Test
    void laNominaPuedeSerSoloUnaParteDelEquipo() {
        integranteNuevo("a_eq");
        Long juega = integranteNuevo("b_eq");

        InscripcionDetalle borrador = inscripcionService.crear(capitan, torneo, existente(equipo, juega));

        assertThat(borrador.nomina()).extracting(UsuarioPublico::id).containsExactlyInAnyOrder(capitan, juega);
    }

    @Test
    void elCapitanFiguraAunqueNoEsteEnLaListaYNoSeDuplicaSiEsta() {
        Long jugador = integranteNuevo("jug_eq");

        assertThat(inscripcionService.crear(capitan, torneo, existente(equipo, jugador, capitan, jugador)).nomina())
                .extracting(UsuarioPublico::id).containsExactlyInAnyOrder(capitan, jugador);
    }

    @Test
    void sinNominaCargadaElBorradorTieneSoloAlCapitan() {
        InscripcionEquipoRequest sinLista = new InscripcionEquipoRequest(equipo, null, null);

        assertThat(inscripcionService.crear(capitan, torneo, sinLista).nomina())
                .extracting(UsuarioPublico::id).containsExactly(capitan);
    }

    @Test
    void losJugadoresQueTodaviaNoSonIntegrantesSeSumanAlEquipo() {
        Long nuevoJugador = usuario("nuevo_eq").getId();

        inscripcionService.crear(capitan, torneo, existente(equipo, nuevoJugador));

        assertThat(integranteActivo(nuevoJugador, equipo)).isTrue();
    }

    @Test
    void unIntegranteDadoDeBajaQueSeVuelveACargarSeReactiva() {
        Long exIntegrante = integranteNuevo("ex_eq");
        jdbc.update("UPDATE usuario_equipo SET eliminado_en = now() WHERE usuario_id = ? AND equipo_id = ?", exIntegrante, equipo);
        assertThat(integranteActivo(exIntegrante, equipo)).isFalse();

        InscripcionDetalle borrador = inscripcionService.crear(capitan, torneo, existente(equipo, exIntegrante));

        assertThat(integranteActivo(exIntegrante, equipo)).isTrue();
        assertThat(borrador.nomina()).extracting(UsuarioPublico::id).contains(exIntegrante);
    }

    @Test
    void reemplazaUnJugadorDeUnaInscripcionConfirmada() {
        Long sale = integranteNuevo("sale_reemplazo_eq");
        Long entra = integranteNuevo("entra_reemplazo_eq");
        long id = inscripcionDeEquipo(torneo, equipo, "CONFIRMADA");
        enNomina(id, capitan, equipo);
        enNomina(id, sale, equipo);

        InscripcionDetalle reemplazada = inscripcionService.reemplazar(capitan, id, new ReemplazoRequest(sale, entra));

        assertThat(reemplazada.estado()).isEqualTo(EstadoInscripcion.CONFIRMADA);
        assertThat(reemplazada.nomina()).extracting(UsuarioPublico::id)
                .containsExactlyInAnyOrder(capitan, entra);
        assertThat(integranteActivo(entra, equipo)).isTrue();
        assertThat(integranteActivo(sale, equipo)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM nomina WHERE inscripcion_id = ?", Integer.class, id))
                .isEqualTo(2);
    }

    @Test
    void registraYDevuelveElHistorialDelMasViejoAlMasNuevo() {
        Long primero = integranteNuevo("primero_reemplazo_eq");
        Long segundo = integranteNuevo("segundo_reemplazo_eq");
        Long tercero = integranteNuevo("tercero_reemplazo_eq");
        long id = inscripcionDeEquipo(torneo, equipo, "CONFIRMADA");
        enNomina(id, capitan, equipo);
        enNomina(id, primero, equipo);

        inscripcionService.reemplazar(capitan, id, new ReemplazoRequest(primero, segundo));
        inscripcionService.reemplazar(capitan, id, new ReemplazoRequest(segundo, tercero));

        List<ReemplazoDetalle> historial = inscripcionService.reemplazos(capitan, id);

        assertThat(historial).hasSize(2);
        assertThat(historial.get(0).sale().id()).isEqualTo(primero);
        assertThat(historial.get(0).entra().id()).isEqualTo(segundo);
        assertThat(historial.get(0).realizadoPor().id()).isEqualTo(capitan);
        assertThat(historial.get(0).reemplazadoEn()).isNotNull();
        assertThat(historial.get(1).sale().id()).isEqualTo(segundo);
        assertThat(historial.get(1).entra().id()).isEqualTo(tercero);
    }

    @Test
    void noPermiteQueElCapitanSalgaNiQueSalgaUnJugadorAusente() {
        Long jugador = integranteNuevo("jugador_reemplazo_eq");
        Long ausente = integranteNuevo("ausente_reemplazo_eq");
        long id = inscripcionDeEquipo(torneo, equipo, "CONFIRMADA");
        enNomina(id, capitan, equipo);
        enNomina(id, jugador, equipo);

        assertError(() -> inscripcionService.reemplazar(capitan, id, new ReemplazoRequest(capitan, ausente)),
                HttpStatus.CONFLICT, CodigoError.REEMPLAZO_INVALIDO);
        assertError(() -> inscripcionService.reemplazar(capitan, id, new ReemplazoRequest(ausente, jugador)),
                HttpStatus.CONFLICT, CodigoError.REEMPLAZO_INVALIDO);
    }

    @Test
    void noPermiteReemplazarsePorElMismoUsuario() {
        Long jugador = integranteNuevo("mismo_reemplazo_eq");
        long id = inscripcionDeEquipo(torneo, equipo, "CONFIRMADA");

        assertError(() -> inscripcionService.reemplazar(capitan, id, new ReemplazoRequest(jugador, jugador)),
                HttpStatus.BAD_REQUEST, CodigoError.VALIDACION);
    }

    @Test
    void elJugadorQueEntraTieneQueExistir() {
        Long sale = integranteNuevo("sale_sin_entrada_eq");
        long id = inscripcionDeEquipo(torneo, equipo, "CONFIRMADA");
        enNomina(id, capitan, equipo);
        enNomina(id, sale, equipo);

        assertError(() -> inscripcionService.reemplazar(capitan, id, new ReemplazoRequest(sale, 999_999L)),
                HttpStatus.NOT_FOUND, CodigoError.USUARIO_NO_ENCONTRADO);
    }

    @Test
    void noPermiteReemplazarCuandoElCupoYaEstaCompleto() {
        Long sale = integranteNuevo("sale_cupo_reemplazo_eq");
        Long entra = integranteNuevo("entra_cupo_reemplazo_eq");
        long id = inscripcionDeEquipo(torneo, equipo, "CONFIRMADA");
        enNomina(id, capitan, equipo);
        enNomina(id, sale, equipo);
        confirmarEquipos(torneo, 7);

        assertError(() -> inscripcionService.reemplazar(capitan, id, new ReemplazoRequest(sale, entra)),
                HttpStatus.CONFLICT, CodigoError.INSCRIPCION_NO_ABIERTA);
    }

    @Test
    void soloSeReemplazaEnUnaInscripcionConfirmada() {
        Long sale = integranteNuevo("sale_pendiente_reemplazo_eq");
        Long entra = integranteNuevo("entra_pendiente_reemplazo_eq");
        long id = inscripcionDeEquipo(torneo, equipo, "PENDIENTE_PAGO");
        enNomina(id, capitan, equipo);
        enNomina(id, sale, equipo);

        assertError(() -> inscripcionService.reemplazar(capitan, id, new ReemplazoRequest(sale, entra)),
                HttpStatus.CONFLICT, CodigoError.INSCRIPCION_NO_EDITABLE);
    }

    @Test
    void noPermiteReemplazarConElTorneoYaCerrado() {
        Long sale = integranteNuevo("sale_cerrado_reemplazo_eq");
        Long entra = integranteNuevo("entra_cerrado_reemplazo_eq");
        long id = inscripcionDeEquipo(torneo, equipo, "CONFIRMADA");
        enNomina(id, capitan, equipo);
        enNomina(id, sale, equipo);
        jdbc.update("UPDATE torneo SET estado = 'INSCRIPCION_CERRADA' WHERE id = ?", torneo);

        assertError(() -> inscripcionService.reemplazar(capitan, id, new ReemplazoRequest(sale, entra)),
                HttpStatus.CONFLICT, CodigoError.INSCRIPCION_NO_ABIERTA);
    }

    @Test
    void noPermiteReemplazarConElCierreDeInscripcionVencido() {
        Long sale = integranteNuevo("sale_vencido_reemplazo_eq");
        Long entra = integranteNuevo("entra_vencido_reemplazo_eq");
        long id = inscripcionDeEquipo(torneo, equipo, "CONFIRMADA");
        enNomina(id, capitan, equipo);
        enNomina(id, sale, equipo);
        jdbc.update("UPDATE torneo SET cierre_inscripcion_en = now() - interval '1 hour' WHERE id = ?", torneo);

        assertError(() -> inscripcionService.reemplazar(capitan, id, new ReemplazoRequest(sale, entra)),
                HttpStatus.CONFLICT, CodigoError.INSCRIPCION_NO_ABIERTA);
    }

    @Test
    void soloElCapitanReemplazaYParaLosDemasLaInscripcionNoExiste() {
        Long jugador = integranteNuevo("jugador_ajeno_reemplazo_eq");
        Long sale = integranteNuevo("sale_ajeno_reemplazo_eq");
        Long entra = integranteNuevo("entra_ajeno_reemplazo_eq");
        long id = inscripcionDeEquipo(torneo, equipo, "CONFIRMADA");
        enNomina(id, capitan, equipo);
        enNomina(id, jugador, equipo);
        enNomina(id, sale, equipo);

        assertError(() -> inscripcionService.reemplazar(jugador, id, new ReemplazoRequest(sale, entra)),
                HttpStatus.NOT_FOUND, CodigoError.INSCRIPCION_NO_ENCONTRADA);
    }

    @Test
    void unaInscripcionIndividualNoTieneNominaParaReemplazar() {
        Long jugador = usuario("indiv_reemplazo_eq").getId();
        Long entra = usuario("entra_indiv_reemplazo_eq").getId();
        long individual = jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, valor_inscripcion, pct_pozo_competidores,
                                    pct_premio_rifa, cierre_inscripcion_en, fecha_inicio, estado)
                VALUES (?, 'Individual', 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE', 'INDIVIDUAL', 8, 2, 1000,
                        50, 10, now() + interval '1 day', now() + interval '7 days', 'INSCRIPCION_ABIERTA')
                RETURNING id
                """, Long.class, organizador);
        long id = jdbc.queryForObject("""
                INSERT INTO inscripcion (torneo_id, usuario_id, estado, reglamento_aceptado_en)
                VALUES (?, ?, 'CONFIRMADA', now())
                RETURNING id
                """, Long.class, individual, jugador);

        assertError(() -> inscripcionService.reemplazar(jugador, id, new ReemplazoRequest(jugador, entra)),
                HttpStatus.CONFLICT, CodigoError.REEMPLAZO_INVALIDO);
    }

    @Test
    void elQueEntraNoPuedeEstarYaEnLaNomina() {
        Long sale = integranteNuevo("sale_repetido_reemplazo_eq");
        Long entra = integranteNuevo("entra_repetido_reemplazo_eq");
        long id = inscripcionDeEquipo(torneo, equipo, "CONFIRMADA");
        enNomina(id, capitan, equipo);
        enNomina(id, sale, equipo);
        enNomina(id, entra, equipo);

        assertError(() -> inscripcionService.reemplazar(capitan, id, new ReemplazoRequest(sale, entra)),
                HttpStatus.CONFLICT, CodigoError.REEMPLAZO_INVALIDO);
    }

    @Test
    void elQueEntraNoPuedeFigurarEnOtraInscripcionActivaDelTorneo() {
        Long sale = integranteNuevo("sale_otro_reemplazo_eq");
        Long entra = usuario("entra_otro_reemplazo_eq").getId();
        long id = inscripcionDeEquipo(torneo, equipo, "CONFIRMADA");
        enNomina(id, capitan, equipo);
        enNomina(id, sale, equipo);
        inscripcionActivaDeOtroEquipoCon(entra, "PENDIENTE_PAGO");

        assertError(() -> inscripcionService.reemplazar(capitan, id, new ReemplazoRequest(sale, entra)),
                HttpStatus.CONFLICT, CodigoError.JUGADOR_YA_INSCRIPTO);
    }

    @Test
    void elQueEntraConUnBloqueoVigenteSeRechazaYNoSeSumaAlEquipo() {
        Long sale = integranteNuevo("sale_bloq_reemplazo_eq");
        Usuario entra = usuario("entra_bloq_reemplazo_eq");
        bloqueoService.bloquear(entra, "Mala conducta", Instant.now().plus(5, ChronoUnit.DAYS));
        long id = inscripcionDeEquipo(torneo, equipo, "CONFIRMADA");
        enNomina(id, capitan, equipo);
        enNomina(id, sale, equipo);

        assertError(() -> inscripcionService.reemplazar(capitan, id, new ReemplazoRequest(sale, entra.getId())),
                HttpStatus.FORBIDDEN, CodigoError.USUARIO_BLOQUEADO);
        assertThat(integranteActivo(entra.getId(), equipo)).isFalse();
    }

    @Test
    void elQueEntraNoActivoSeRechazaYNoSeSumaAlEquipo() {
        Long sale = integranteNuevo("sale_pend_reemplazo_eq");
        Usuario entra = usuario("entra_pend_reemplazo_eq");
        entra.setEstado(EstadoUsuario.PENDIENTE_VERIFICACION);
        usuarioRepository.saveAndFlush(entra);
        long id = inscripcionDeEquipo(torneo, equipo, "CONFIRMADA");
        enNomina(id, capitan, equipo);
        enNomina(id, sale, equipo);

        assertError(() -> inscripcionService.reemplazar(capitan, id, new ReemplazoRequest(sale, entra.getId())),
                HttpStatus.CONFLICT, CodigoError.USUARIO_NO_ACTIVO);
        assertThat(integranteActivo(entra.getId(), equipo)).isFalse();
    }

    @Test
    void elOrganizadorDelTorneoNoEntraEnUnaNomina() {
        Long sale = integranteNuevo("sale_org_reemplazo_eq");
        long id = inscripcionDeEquipo(torneo, equipo, "CONFIRMADA");
        enNomina(id, capitan, equipo);
        enNomina(id, sale, equipo);

        assertError(() -> inscripcionService.reemplazar(capitan, id, new ReemplazoRequest(sale, organizador)),
                HttpStatus.FORBIDDEN, CodigoError.INSCRIPCION_NO_PERMITIDA_EN_TORNEO);
    }

    @Test
    void unArbitroDelTorneoNoEntraEnUnaNomina() {
        Long sale = integranteNuevo("sale_arbitro_reemplazo_eq");
        Long arbitro = integranteNuevo("arbitro_reemplazo_eq");
        jdbc.update("INSERT INTO torneo_arbitro (torneo_id, usuario_id) VALUES (?, ?)", torneo, arbitro);
        long id = inscripcionDeEquipo(torneo, equipo, "CONFIRMADA");
        enNomina(id, capitan, equipo);
        enNomina(id, sale, equipo);

        assertError(() -> inscripcionService.reemplazar(capitan, id, new ReemplazoRequest(sale, arbitro)),
                HttpStatus.FORBIDDEN, CodigoError.INSCRIPCION_NO_PERMITIDA_EN_TORNEO);
    }

    @Test
    void elIntegranteDadoDeBajaQueEntraSeReactiva() {
        Long sale = integranteNuevo("sale_baja_reemplazo_eq");
        Long exIntegrante = integranteNuevo("ex_baja_reemplazo_eq");
        jdbc.update("UPDATE usuario_equipo SET eliminado_en = now() WHERE usuario_id = ? AND equipo_id = ?", exIntegrante, equipo);
        long id = inscripcionDeEquipo(torneo, equipo, "CONFIRMADA");
        enNomina(id, capitan, equipo);
        enNomina(id, sale, equipo);

        InscripcionDetalle reemplazada = inscripcionService.reemplazar(capitan, id, new ReemplazoRequest(sale, exIntegrante));

        assertThat(integranteActivo(exIntegrante, equipo)).isTrue();
        assertThat(reemplazada.nomina()).extracting(UsuarioPublico::id).containsExactlyInAnyOrder(capitan, exIntegrante);
    }

    @Test
    void unReemplazoRechazadoNoTocaLaNominaNiElEquipoNiElHistorial() {
        Long sale = integranteNuevo("sale_rechazo_reemplazo_eq");
        Long entra = usuario("entra_rechazo_reemplazo_eq").getId();
        long id = inscripcionDeEquipo(torneo, equipo, "CONFIRMADA");
        enNomina(id, capitan, equipo);
        enNomina(id, sale, equipo);
        inscripcionActivaDeOtroEquipoCon(entra, "CONFIRMADA");

        assertError(() -> inscripcionService.reemplazar(capitan, id, new ReemplazoRequest(sale, entra)),
                HttpStatus.CONFLICT, CodigoError.JUGADOR_YA_INSCRIPTO);

        assertThat(jdbc.queryForList("SELECT usuario_id FROM nomina WHERE inscripcion_id = ?", Long.class, id))
                .containsExactlyInAnyOrder(capitan, sale);
        assertThat(integranteActivo(entra, equipo)).isFalse();
        assertThat(inscripcionService.reemplazos(capitan, id)).isEmpty();
    }

    @Test
    void elOrganizadorYLosDeLaNominaVenElHistorial() {
        Long jugador = integranteNuevo("jugador_historial_eq");
        Long sale = integranteNuevo("sale_historial_eq");
        Long entra = integranteNuevo("entra_historial_eq");
        long id = inscripcionDeEquipo(torneo, equipo, "CONFIRMADA");
        enNomina(id, capitan, equipo);
        enNomina(id, jugador, equipo);
        enNomina(id, sale, equipo);
        inscripcionService.reemplazar(capitan, id, new ReemplazoRequest(sale, entra));

        assertThat(inscripcionService.reemplazos(organizador, id)).extracting(r -> r.entra().id()).containsExactly(entra);
        assertThat(inscripcionService.reemplazos(jugador, id)).extracting(r -> r.entra().id()).containsExactly(entra);
        assertThat(inscripcionService.reemplazos(entra, id)).extracting(r -> r.entra().id()).containsExactly(entra);
    }

    @Test
    void paraQuienNoEstaEnLaInscripcionElHistorialNoExiste() {
        Long ajeno = usuario("ajeno_historial_eq").getId();
        Long fueraDeLaNomina = integranteNuevo("fuera_historial_eq");
        long id = inscripcionDeEquipo(torneo, equipo, "CONFIRMADA");
        enNomina(id, capitan, equipo);

        assertError(() -> inscripcionService.reemplazos(ajeno, id), HttpStatus.NOT_FOUND, CodigoError.INSCRIPCION_NO_ENCONTRADA);
        assertError(() -> inscripcionService.reemplazos(fueraDeLaNomina, id), HttpStatus.NOT_FOUND, CodigoError.INSCRIPCION_NO_ENCONTRADA);
    }

    @Test
    void sinReemplazosElHistorialEstaVacioAunqueOtraInscripcionLosTenga() {
        long id = inscripcionDeEquipo(torneo, equipo, "CONFIRMADA");
        enNomina(id, capitan, equipo);
        Long otroCapitan = usuario("cap_otro_historial_eq").getId();
        Long otroEquipo = equipo("Otro historial", otroCapitan);
        Long sale = usuario("sale_otro_historial_eq").getId();
        Long entra = usuario("entra_otro_historial_eq").getId();
        integrante(otroEquipo, sale);
        long otra = inscripcionDeEquipo(torneo, otroEquipo, "CONFIRMADA");
        enNomina(otra, otroCapitan, otroEquipo);
        enNomina(otra, sale, otroEquipo);
        inscripcionService.reemplazar(otroCapitan, otra, new ReemplazoRequest(sale, entra));

        assertThat(inscripcionService.reemplazos(capitan, id)).isEmpty();
    }

    @Test
    void unEquipoNuevoSumaALosJugadoresDeLaNomina() {
        Long jugador = usuario("jug_nuevo_eq").getId();

        InscripcionDetalle borrador = inscripcionService.crear(capitan, torneo, nuevo("Los Zorros", jugador));

        assertThat(integranteActivo(jugador, borrador.equipo().id())).isTrue();
        assertThat(borrador.nomina()).extracting(UsuarioPublico::id).containsExactlyInAnyOrder(capitan, jugador);
    }

    @Test
    void unJugadorQueNoExisteSeRechaza() {
        assertError(() -> inscripcionService.crear(capitan, torneo, existente(equipo, 999_999L)), HttpStatus.NOT_FOUND, CodigoError.USUARIO_NO_ENCONTRADO);
    }

    @Test
    void unJugadorNoActivoSeRechazaYNoSeSumaAlEquipo() {
        Usuario sinVerificar = usuario("pend_eq");
        sinVerificar.setEstado(EstadoUsuario.PENDIENTE_VERIFICACION);
        usuarioRepository.saveAndFlush(sinVerificar);

        assertError(() -> inscripcionService.crear(capitan, torneo, existente(equipo, sinVerificar.getId())), HttpStatus.CONFLICT, CodigoError.USUARIO_NO_ACTIVO);
        assertThat(integranteActivo(sinVerificar.getId(), equipo)).isFalse();
    }

    @Test
    void unJugadorConUnBloqueoVigenteSeRechaza() {
        Usuario bloqueado = usuario("bloq_eq");
        bloqueoService.bloquear(bloqueado, "Mala conducta", Instant.now().plus(5, ChronoUnit.DAYS));

        assertError(() -> inscripcionService.crear(capitan, torneo, existente(equipo, bloqueado.getId())), HttpStatus.FORBIDDEN, CodigoError.USUARIO_BLOQUEADO);
        assertThat(integranteActivo(bloqueado.getId(), equipo)).isFalse();
    }

    // --- duplicados ---------------------------------------------------------

    @Test
    void elMismoEquipoConUnaInscripcionActivaEnElTorneoNoSeInscribeDeNuevo() {
        for (String estado : new String[] {"PENDIENTE_PAGO", "CONFIRMADA"}) {
            long otroTorneo = torneoPorEquipos(5, 8);
            inscripcionDeEquipo(otroTorneo, equipo, estado);

            assertError(() -> inscripcionService.crear(capitan, otroTorneo, existente(equipo)), HttpStatus.CONFLICT, CodigoError.INSCRIPCION_DUPLICADA);
        }
    }

    @Test
    void unCapitanConUnBorradorEnElTorneoNoCreaOtroConUnEquipoDistinto() {
        Long otroEquipo = equipo("Los Gavilanes", capitan);
        long idDelBorrador = inscripcionDeEquipo(torneo, equipo, "BORRADOR");

        ApiException error = catchThrowableOfType(ApiException.class,
                () -> inscripcionService.crear(capitan, torneo, existente(otroEquipo)));

        assertThat(error).isNotNull();
        assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error.getCodigo()).isEqualTo(CodigoError.INSCRIPCION_DUPLICADA);
        assertThat(error.getMessage()).contains("borrador " + idDelBorrador);
    }

    @Test
    void elBorradorRechazadoNoDejaUnEquipoNuevoCreado() {
        inscripcionDeEquipo(torneo, equipo, "BORRADOR");

        assertError(() -> inscripcionService.crear(capitan, torneo, nuevo("Los Zorros")), HttpStatus.CONFLICT, CodigoError.INSCRIPCION_DUPLICADA);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM equipo WHERE nombre = 'Los Zorros'", Integer.class)).isZero();
    }

    @Test
    void elBorradorCanceladoDelCapitanNoImpideCrearOtroConOtroEquipo() {
        Long otroEquipo = equipo("Los Gavilanes", capitan);
        inscripcionDeEquipo(torneo, equipo, "CANCELADA");

        assertThat(inscripcionService.crear(capitan, torneo, existente(otroEquipo)).estado()).isEqualTo(EstadoInscripcion.BORRADOR);
    }

    @Test
    void elBorradorDelCapitanEnOtroTorneoNoImpideCrearEnEste() {
        inscripcionDeEquipo(torneoPorEquipos(5, 8), equipo, "BORRADOR");
        Long otroEquipo = equipo("Los Gavilanes", capitan);

        assertThat(inscripcionService.crear(capitan, torneo, existente(otroEquipo)).estado()).isEqualTo(EstadoInscripcion.BORRADOR);
    }

    // La clave única de torneo y equipo de V9 se mantiene: un equipo tiene una sola inscripción por torneo,
    // sea cual sea su estado. Si ya tiene un borrador se edita ese (lo corta la regla del borrador único), y una
    // cancelada no se vuelve a abrir.
    @Test
    void elMismoEquipoConUnBorradorEnElTorneoNoCreaOtroEnVezDeUnErrorDeLaBase() {
        inscripcionDeEquipo(torneo, equipo, "BORRADOR");

        assertError(() -> inscripcionService.crear(capitan, torneo, existente(equipo)), HttpStatus.CONFLICT, CodigoError.INSCRIPCION_DUPLICADA);
    }

    @Test
    void elMismoEquipoConUnaInscripcionCanceladaEnElTorneoTampocoVuelveAInscribirse() {
        inscripcionDeEquipo(torneo, equipo, "CANCELADA");

        assertError(() -> inscripcionService.crear(capitan, torneo, existente(equipo)), HttpStatus.CONFLICT, CodigoError.INSCRIPCION_DUPLICADA);
    }

    @Test
    void unJugadorQueYaFiguraEnLaNominaDeOtraInscripcionActivaSeRechaza() {
        Long compartido = usuario("compartido_eq").getId();
        integrante(equipo, compartido);
        inscripcionActivaDeOtroEquipoCon(compartido, "PENDIENTE_PAGO");

        assertError(() -> inscripcionService.crear(capitan, torneo, existente(equipo, compartido)), HttpStatus.CONFLICT, CodigoError.JUGADOR_YA_INSCRIPTO);
    }

    @Test
    void tambienSeRechazaAlQueYaEstaConfirmadoEnOtroEquipo() {
        Long compartido = usuario("compartido2_eq").getId();
        inscripcionActivaDeOtroEquipoCon(compartido, "CONFIRMADA");

        assertError(() -> inscripcionService.crear(capitan, torneo, nuevo("Los Zorros", compartido)), HttpStatus.CONFLICT, CodigoError.JUGADOR_YA_INSCRIPTO);
    }

    @Test
    void elCapitanQueYaFiguraEnOtraInscripcionActivaDelTorneoSeRechaza() {
        Long otroEquipo = equipo("Los Gavilanes", capitan);
        long activa = inscripcionDeEquipo(torneo, otroEquipo, "CONFIRMADA");
        enNomina(activa, capitan, otroEquipo);

        assertError(() -> inscripcionService.crear(capitan, torneo, existente(equipo)), HttpStatus.CONFLICT, CodigoError.JUGADOR_YA_INSCRIPTO);
    }

    @Test
    void losBorradoresYLasCanceladasDeOtrosEquiposNoCuentanComoInscripcion() {
        Long compartido = usuario("compartido3_eq").getId();
        integrante(equipo, compartido);
        inscripcionActivaDeOtroEquipoCon(compartido, "BORRADOR");
        inscripcionActivaDeOtroEquipoCon(compartido, "CANCELADA");

        assertThat(inscripcionService.crear(capitan, torneo, existente(equipo, compartido)).nomina())
                .extracting(UsuarioPublico::id).contains(compartido);
    }

    @Test
    void laInscripcionActivaDeOtroTorneoNoImpideInscribirseAcaso() {
        Long compartido = usuario("compartido4_eq").getId();
        integrante(equipo, compartido);
        long otroTorneo = torneoPorEquipos(5, 8);
        Long otroEquipo = equipo("Los Gavilanes", usuario("cap3_eq").getId());
        integrante(otroEquipo, compartido);
        long activa = inscripcionDeEquipo(otroTorneo, otroEquipo, "CONFIRMADA");
        enNomina(activa, compartido, otroEquipo);

        assertThat(inscripcionService.crear(capitan, torneo, existente(equipo, compartido)).estado()).isEqualTo(EstadoInscripcion.BORRADOR);
    }

    // --- editar -------------------------------------------------------------

    @Test
    void editarReemplazaLaNominaDelBorrador() {
        Long sale = integranteNuevo("sale_eq");
        Long entra = integranteNuevo("entra_eq");
        long id = inscripcionService.crear(capitan, torneo, existente(equipo, sale)).id();

        InscripcionDetalle editado = inscripcionService.editar(capitan, id, existente(equipo, entra));

        assertThat(editado.id()).isEqualTo(id);
        assertThat(editado.nomina()).extracting(UsuarioPublico::id).containsExactlyInAnyOrder(capitan, entra);
        assertThat(inscripcionService.detalle(capitan, id).nomina()).extracting(UsuarioPublico::id)
                .containsExactlyInAnyOrder(capitan, entra);
    }

    @Test
    void editarSumaAlEquipoALosJugadoresNuevos() {
        long id = inscripcionService.crear(capitan, torneo, existente(equipo)).id();
        Long nuevoJugador = usuario("nuevo2_eq").getId();

        inscripcionService.editar(capitan, id, existente(equipo, nuevoJugador));

        assertThat(integranteActivo(nuevoJugador, equipo)).isTrue();
    }

    @Test
    void editarPuedeCambiarElEquipoPorOtroDelCapitan() {
        Long jugador = integranteNuevo("jug2_eq");
        long id = inscripcionService.crear(capitan, torneo, existente(equipo, jugador)).id();
        Long otroEquipo = equipo("Los Gavilanes", capitan);

        InscripcionDetalle editado = inscripcionService.editar(capitan, id, existente(otroEquipo));

        assertThat(editado.equipo().id()).isEqualTo(otroEquipo);
        assertThat(editado.nomina()).extracting(UsuarioPublico::id).containsExactly(capitan);
    }

    @Test
    void editarPuedeCambiarElEquipoPorUnoNuevo() {
        long id = inscripcionService.crear(capitan, torneo, existente(equipo)).id();

        InscripcionDetalle editado = inscripcionService.editar(capitan, id, nuevo("Los Zorros"));

        assertThat(editado.equipo().nombre()).isEqualTo("Los Zorros");
        assertThat(editado.nomina()).extracting(UsuarioPublico::id).containsExactly(capitan);
    }

    @Test
    void editarElBorradorConElMismoEquipoNoEsUnDuplicadoDeSiMismo() {
        long id = inscripcionService.crear(capitan, torneo, existente(equipo)).id();

        assertThat(inscripcionService.editar(capitan, id, existente(equipo)).estado()).isEqualTo(EstadoInscripcion.BORRADOR);
    }

    @Test
    void soloSeEditaUnBorrador() {
        for (String estado : new String[] {"PENDIENTE_PAGO", "CONFIRMADA", "CANCELADA"}) {
            Long otroEquipo = equipo("Equipo " + estado, capitan);
            long id = inscripcionDeEquipo(torneo, otroEquipo, estado);

            assertError(() -> inscripcionService.editar(capitan, id, existente(otroEquipo)), HttpStatus.CONFLICT, CodigoError.INSCRIPCION_NO_EDITABLE);
        }
    }

    @Test
    void soloElCapitanEditaElBorrador() {
        Long integrante = integranteNuevo("integ_eq");
        long id = inscripcionService.crear(capitan, torneo, existente(equipo, integrante)).id();

        assertError(() -> inscripcionService.editar(integrante, id, existente(equipo)), HttpStatus.NOT_FOUND, CodigoError.INSCRIPCION_NO_ENCONTRADA);
        assertError(() -> inscripcionService.editar(organizador, id, existente(equipo)), HttpStatus.NOT_FOUND, CodigoError.INSCRIPCION_NO_ENCONTRADA);
        assertError(() -> inscripcionService.editar(capitan, 999_999L, existente(equipo)), HttpStatus.NOT_FOUND, CodigoError.INSCRIPCION_NO_ENCONTRADA);
    }

    @Test
    void unEquipoQueNoEsDelCapitanNoSeUsaAlEditar() {
        long id = inscripcionService.crear(capitan, torneo, existente(equipo)).id();
        Long ajeno = equipo("Ajeno", usuario("cap_ajeno_eq").getId());

        assertError(() -> inscripcionService.editar(capitan, id, existente(ajeno)), HttpStatus.NOT_FOUND, CodigoError.EQUIPO_NO_ENCONTRADO);
    }

    @Test
    void alEditarSeVuelveAChequearQueElJugadorNoFigureEnOtraInscripcionActiva() {
        long id = inscripcionService.crear(capitan, torneo, existente(equipo)).id();
        Long compartido = usuario("compartido5_eq").getId();
        integrante(equipo, compartido);
        inscripcionActivaDeOtroEquipoCon(compartido, "CONFIRMADA");

        assertError(() -> inscripcionService.editar(capitan, id, existente(equipo, compartido)), HttpStatus.CONFLICT, CodigoError.JUGADOR_YA_INSCRIPTO);
    }

    @Test
    void alEditarElTorneoTieneQueSeguirConLaInscripcionAbierta() {
        long id = inscripcionDeEquipo(torneo, equipo, "BORRADOR");
        jdbc.update("UPDATE torneo SET estado = 'INSCRIPCION_CERRADA' WHERE id = ?", torneo);

        assertError(() -> inscripcionService.editar(capitan, id, existente(equipo)), HttpStatus.CONFLICT, CodigoError.INSCRIPCION_NO_ABIERTA);
    }

    @Test
    void unaInscripcionIndividualNoTieneEquipoNiNomina() {
        Long jugador = usuario("indiv_eq").getId();
        long individual = jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, valor_inscripcion, pct_pozo_competidores,
                                    pct_premio_rifa, cierre_inscripcion_en, fecha_inicio, estado)
                VALUES (?, 'Individual', 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE', 'INDIVIDUAL', 8, 2, 1000,
                        50, 10, now() + interval '1 day', now() + interval '7 days', 'INSCRIPCION_ABIERTA')
                RETURNING id
                """, Long.class, organizador);
        long id = inscripcionService.crear(jugador, individual, null).id();

        assertError(() -> inscripcionService.editar(jugador, id, existente(equipo)), HttpStatus.BAD_REQUEST, CodigoError.VALIDACION);
    }

    // --- más reglas al crear y al editar ------------------------------------

    @Test
    void siGanaOtroPedidoSimultaneoDelMismoEquipoRespondeDuplicadaYNoUnErrorDeLaBase() {
        inscripcionDeEquipo(torneo, equipo, "BORRADOR");
        // los chequeos previos no ven a la otra inscripción, pero la clave única de la base sí
        doReturn(List.of()).when(inscripcionRepository).borradoresDe(anyLong(), anyLong());
        doReturn(Optional.empty()).when(inscripcionRepository).findByTorneoIdAndEquipoId(anyLong(), anyLong());

        assertError(() -> inscripcionService.crear(capitan, torneo, existente(equipo)), HttpStatus.CONFLICT, CodigoError.INSCRIPCION_DUPLICADA);
    }

    @Test
    void editarHaciaUnEquipoQueYaTieneOtraInscripcionEsDuplicada() {
        Long otroEquipo = equipo("Los Gavilanes", capitan);
        inscripcionDeEquipo(torneo, otroEquipo, "BORRADOR");
        long id = inscripcionDeEquipo(torneo, equipo, "BORRADOR");

        assertError(() -> inscripcionService.editar(capitan, id, existente(otroEquipo)), HttpStatus.CONFLICT, CodigoError.INSCRIPCION_DUPLICADA);
    }

    @Test
    void editarSiGanaOtroPedidoSimultaneoDelEquipoDestinoRespondeDuplicada() {
        Long otroEquipo = equipo("Los Gavilanes", capitan);
        inscripcionDeEquipo(torneo, otroEquipo, "BORRADOR");
        long id = inscripcionDeEquipo(torneo, equipo, "BORRADOR");
        doReturn(Optional.empty()).when(inscripcionRepository).findByTorneoIdAndEquipoId(anyLong(), anyLong());

        assertError(() -> inscripcionService.editar(capitan, id, existente(otroEquipo)), HttpStatus.CONFLICT, CodigoError.INSCRIPCION_DUPLICADA);
    }

    @Test
    void editarReactivaAlIntegranteDadoDeBajaQueSeVuelveACargar() {
        Long exIntegrante = integranteNuevo("ex2_eq");
        jdbc.update("UPDATE usuario_equipo SET eliminado_en = now() WHERE usuario_id = ? AND equipo_id = ?", exIntegrante, equipo);
        long id = inscripcionService.crear(capitan, torneo, existente(equipo)).id();

        inscripcionService.editar(capitan, id, existente(equipo, exIntegrante));

        assertThat(integranteActivo(exIntegrante, equipo)).isTrue();
    }

    @Test
    void siUnJugadorEsInvalidoNoSeCreaElEquipoNuevoNiSeSumaANadie() {
        Usuario sinVerificar = usuario("pend2_eq");
        sinVerificar.setEstado(EstadoUsuario.PENDIENTE_VERIFICACION);
        usuarioRepository.saveAndFlush(sinVerificar);
        Long valido = usuario("valido_eq").getId();

        assertError(() -> inscripcionService.crear(capitan, torneo, nuevo("Los Zorros", valido, sinVerificar.getId())),
                HttpStatus.CONFLICT, CodigoError.USUARIO_NO_ACTIVO);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM equipo WHERE nombre = 'Los Zorros'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usuario_equipo WHERE usuario_id = ?", Integer.class, valido)).isZero();
    }

    @Test
    void alEditarSiUnJugadorEsInvalidoNoSeEscribeNada() {
        long id = inscripcionDeEquipo(torneo, equipo, "BORRADOR");
        enNomina(id, capitan, equipo);
        Usuario sinVerificar = usuario("pend3_eq");
        sinVerificar.setEstado(EstadoUsuario.PENDIENTE_VERIFICACION);
        usuarioRepository.saveAndFlush(sinVerificar);

        assertError(() -> inscripcionService.editar(capitan, id, nuevo("Los Zorros", sinVerificar.getId())),
                HttpStatus.CONFLICT, CodigoError.USUARIO_NO_ACTIVO);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM equipo WHERE nombre = 'Los Zorros'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM nomina WHERE inscripcion_id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void alEditarUnJugadorQueYaFiguraEnOtraInscripcionActivaSeRechazaAunqueSeaCapitanDeOtroEquipo() {
        long id = inscripcionDeEquipo(torneo, equipo, "BORRADOR");
        Long otroCapitan = usuario("cap4_eq").getId();
        Long otroEquipo = equipo("Los Gavilanes", otroCapitan);
        long activa = inscripcionDeEquipo(torneo, otroEquipo, "PENDIENTE_PAGO");
        enNomina(activa, otroCapitan, otroEquipo);
        integrante(equipo, otroCapitan);

        assertError(() -> inscripcionService.editar(capitan, id, existente(equipo, otroCapitan)), HttpStatus.CONFLICT, CodigoError.JUGADOR_YA_INSCRIPTO);
    }

    @Test
    void alEditarElCapitanNoPuedeHaberPasadoAArbitroDelTorneo() {
        long id = inscripcionDeEquipo(torneo, equipo, "BORRADOR");
        jdbc.update("INSERT INTO torneo_arbitro (torneo_id, usuario_id) VALUES (?, ?)", torneo, capitan);

        assertError(() -> inscripcionService.editar(capitan, id, existente(equipo)), HttpStatus.FORBIDDEN, CodigoError.INSCRIPCION_NO_PERMITIDA_EN_TORNEO);
    }

    // --- confirmar ----------------------------------------------------------

    @Test
    void confirmarUnaNominaValidaLaDejaPendienteDePagoConLaFechaDelReglamento() {
        long dosATres = torneoPorEquipos(2, 3);
        Long jugador = integranteNuevo("conf1_eq");
        long id = inscripcionService.crear(capitan, dosATres, existente(equipo, jugador)).id();
        Instant antes = Instant.now();

        InscripcionDetalle confirmada = inscripcionService.confirmar(capitan, id, true);

        assertThat(confirmada.estado()).isEqualTo(EstadoInscripcion.PENDIENTE_PAGO);
        assertThat(confirmada.reglamentoAceptadoEn()).isBetween(antes.minusSeconds(1), Instant.now().plusSeconds(1));
        assertThat(confirmada.nomina()).extracting(UsuarioPublico::id).containsExactlyInAnyOrder(capitan, jugador);
    }

    @Test
    void elCapitanCuentaDentroDeLaNomina() {
        long unoSolo = torneoPorEquipos(1, 1);
        long id = inscripcionService.crear(capitan, unoSolo, existente(equipo)).id();

        assertThat(inscripcionService.confirmar(capitan, id, true).estado()).isEqualTo(EstadoInscripcion.PENDIENTE_PAGO);
    }

    @Test
    void conMenosJugadoresQueElTamanioDeEquipoNoSeConfirma() {
        long dosATres = torneoPorEquipos(2, 3);
        long id = inscripcionService.crear(capitan, dosATres, existente(equipo)).id();

        assertError(() -> inscripcionService.confirmar(capitan, id, true), HttpStatus.CONFLICT, CodigoError.NOMINA_INVALIDA);

        InscripcionDetalle vista = inscripcionService.detalle(capitan, id);
        assertThat(vista.estado()).isEqualTo(EstadoInscripcion.BORRADOR);
        assertThat(vista.reglamentoAceptadoEn()).isNull();
    }

    @Test
    void conMasJugadoresQueElMaximoDeNominaNoSeConfirma() {
        long dosATres = torneoPorEquipos(2, 3);
        Long a = integranteNuevo("max1_eq");
        Long b = integranteNuevo("max2_eq");
        Long c = integranteNuevo("max3_eq");
        long id = inscripcionService.crear(capitan, dosATres, existente(equipo, a, b, c)).id();

        assertError(() -> inscripcionService.confirmar(capitan, id, true), HttpStatus.CONFLICT, CodigoError.NOMINA_INVALIDA);
    }

    @Test
    void conElMaximoExactoDeNominaSeConfirma() {
        long dosATres = torneoPorEquipos(2, 3);
        Long a = integranteNuevo("exacto1_eq");
        Long b = integranteNuevo("exacto2_eq");
        long id = inscripcionService.crear(capitan, dosATres, existente(equipo, a, b)).id();

        assertThat(inscripcionService.confirmar(capitan, id, true).estado()).isEqualTo(EstadoInscripcion.PENDIENTE_PAGO);
    }

    @Test
    void unaNominaSinElCapitanNoSeConfirma() {
        long unoOdos = torneoPorEquipos(1, 2);
        Long jugador = integranteNuevo("sincap_eq");
        long id = inscripcionDeEquipo(unoOdos, equipo, "BORRADOR");
        enNomina(id, jugador, equipo); // la cantidad de jugadores alcanza, pero el capitán no figura

        assertError(() -> inscripcionService.confirmar(capitan, id, true), HttpStatus.CONFLICT, CodigoError.NOMINA_INVALIDA);
    }

    @Test
    void alConfirmarSeVuelveAChequearACadaJugadorDeLaNomina() {
        long dosATres = torneoPorEquipos(2, 3);
        Usuario sinVerificar = usuario("pend4_eq");
        long id = inscripcionService.crear(capitan, dosATres, existente(equipo, sinVerificar.getId())).id();
        sinVerificar.setEstado(EstadoUsuario.PENDIENTE_VERIFICACION);
        usuarioRepository.saveAndFlush(sinVerificar);

        assertError(() -> inscripcionService.confirmar(capitan, id, true), HttpStatus.CONFLICT, CodigoError.USUARIO_NO_ACTIVO);
    }

    @Test
    void unJugadorBloqueadoDespuesDelBorradorNoDejaConfirmar() {
        long dosATres = torneoPorEquipos(2, 3);
        Usuario bloqueado = usuario("bloq2_eq");
        long id = inscripcionService.crear(capitan, dosATres, existente(equipo, bloqueado.getId())).id();
        bloqueoService.bloquear(bloqueado, "Mala conducta", Instant.now().plus(5, ChronoUnit.DAYS));

        assertError(() -> inscripcionService.confirmar(capitan, id, true), HttpStatus.FORBIDDEN, CodigoError.USUARIO_BLOQUEADO);
    }

    @Test
    void unJugadorQueQuedoInscriptoEnOtroEquipoMientrasTantoNoDejaConfirmar() {
        long dosATres = torneoPorEquipos(2, 3);
        Long compartido = usuario("compartido6_eq").getId();
        long id = inscripcionService.crear(capitan, dosATres, existente(equipo, compartido)).id();
        // otro capitán confirmó antes a un equipo con el mismo jugador
        Long otroCapitan = usuario("cap5_eq").getId();
        Long otroEquipo = equipo("Los Gavilanes", otroCapitan);
        integrante(otroEquipo, compartido);
        long activa = inscripcionDeEquipo(dosATres, otroEquipo, "PENDIENTE_PAGO");
        enNomina(activa, otroCapitan, otroEquipo);
        enNomina(activa, compartido, otroEquipo);

        assertError(() -> inscripcionService.confirmar(capitan, id, true), HttpStatus.CONFLICT, CodigoError.JUGADOR_YA_INSCRIPTO);
        assertThat(inscripcionService.detalle(capitan, id).estado()).isEqualTo(EstadoInscripcion.BORRADOR);
    }

    @Test
    void soloElCapitanConfirmaElBorradorDeEquipo() {
        long dosATres = torneoPorEquipos(2, 3);
        Long jugador = integranteNuevo("conf2_eq");
        long id = inscripcionService.crear(capitan, dosATres, existente(equipo, jugador)).id();

        assertError(() -> inscripcionService.confirmar(jugador, id, true), HttpStatus.NOT_FOUND, CodigoError.INSCRIPCION_NO_ENCONTRADA);
        assertError(() -> inscripcionService.confirmar(organizador, id, true), HttpStatus.NOT_FOUND, CodigoError.INSCRIPCION_NO_ENCONTRADA);
        assertThat(inscripcionService.detalle(capitan, id).estado()).isEqualTo(EstadoInscripcion.BORRADOR);
    }

    @Test
    void sinAceptarElReglamentoNoSeConfirmaElBorradorDeEquipo() {
        long unoSolo = torneoPorEquipos(1, 1);
        long id = inscripcionService.crear(capitan, unoSolo, existente(equipo)).id();

        assertError(() -> inscripcionService.confirmar(capitan, id, false), HttpStatus.CONFLICT, CodigoError.REGLAMENTO_NO_ACEPTADO);
    }

    @Test
    void enUnTorneoPorEquiposGratisLaInscripcionQuedaConfirmadaSinPagar() {
        long gratis = torneoPorEquipos(1, 1);
        jdbc.update("UPDATE torneo SET valor_inscripcion = 0 WHERE id = ?", gratis);
        long id = inscripcionService.crear(capitan, gratis, existente(equipo)).id();

        assertThat(inscripcionService.confirmar(capitan, id, true).estado()).isEqualTo(EstadoInscripcion.CONFIRMADA);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento WHERE inscripcion_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void enUnTorneoPorEquiposGratisConElCupoCompletoNoSeConfirma() {
        long gratis = torneoPorEquipos(1, 1);
        jdbc.update("UPDATE torneo SET valor_inscripcion = 0, cupo_maximo = 2 WHERE id = ?", gratis);
        confirmarEquipos(gratis, 2);
        long id = inscripcionService.crear(capitan, gratis, existente(equipo)).id();

        assertError(() -> inscripcionService.confirmar(capitan, id, true), HttpStatus.CONFLICT, CodigoError.CUPO_COMPLETO);
    }

    // --- resumen de pago y pago ---------------------------------------------

    @Test
    void elResumenDeUnaInscripcionDeEquipoMuestraElEquipoYNoUnParticipante() {
        long dosATres = torneoPorEquipos(2, 3);
        Long jugador = integranteNuevo("res1_eq");
        long id = inscripcionService.crear(capitan, dosATres, existente(equipo, jugador)).id();
        inscripcionService.confirmar(capitan, id, true);

        ResumenPago resumen = inscripcionService.resumenPago(capitan, id);

        assertThat(resumen.inscripcionId()).isEqualTo(id);
        assertThat(resumen.equipo().id()).isEqualTo(equipo);
        assertThat(resumen.equipo().nombre()).isEqualTo("Los Halcones");
        assertThat(resumen.participante()).isNull();
        assertThat(resumen.monto()).isEqualByComparingTo("1000");
        assertThat(resumen.torneo().id()).isEqualTo(dosATres);
        assertThat(resumen.cupoDisponible()).isEqualTo(8);
    }

    @Test
    void elResumenDeEquipoSoloLoVeElCapitan() {
        long dosATres = torneoPorEquipos(2, 3);
        Long jugador = integranteNuevo("res2_eq");
        long id = inscripcionService.crear(capitan, dosATres, existente(equipo, jugador)).id();
        inscripcionService.confirmar(capitan, id, true);

        assertError(() -> inscripcionService.resumenPago(jugador, id), HttpStatus.NOT_FOUND, CodigoError.INSCRIPCION_NO_ENCONTRADA);
        assertError(() -> inscripcionService.resumenPago(organizador, id), HttpStatus.NOT_FOUND, CodigoError.INSCRIPCION_NO_ENCONTRADA);
    }

    @Test
    void elCapitanPagaLaInscripcionDeEquipoYElCupoDisponibleBajaEnUno() {
        long dosATres = torneoPorEquipos(2, 3);
        Long jugador = integranteNuevo("pago1_eq");
        long id = inscripcionService.crear(capitan, dosATres, existente(equipo, jugador)).id();
        inscripcionService.confirmar(capitan, id, true);
        assertThat(inscripcionService.resumenPago(capitan, id).cupoDisponible()).isEqualTo(8);

        InscripcionDetalle pagada = inscripcionService.pagar(capitan, id);

        assertThat(pagada.estado()).isEqualTo(EstadoInscripcion.CONFIRMADA);
        assertThat(pagada.nomina()).extracting(UsuarioPublico::id).containsExactlyInAnyOrder(capitan, jugador);
        Map<String, Object> cobro = jdbc.queryForMap("""
                SELECT m.tipo, m.estado, m.monto, b.usuario_id
                FROM movimiento m JOIN billetera b ON b.id = m.billetera_id
                WHERE m.inscripcion_id = ?
                """, id);
        assertThat(cobro).containsEntry("tipo", "INSCRIPCION").containsEntry("estado", "CONFIRMADO").containsEntry("usuario_id", capitan);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inscripcion WHERE torneo_id = ? AND estado = 'CONFIRMADA'", Integer.class, dosATres))
                .isEqualTo(1);
    }

    @Test
    void reintentarElPagoDeEquipoYaConfirmadoNoCobraDeNuevo() {
        long dosATres = torneoPorEquipos(2, 3);
        Long jugador = integranteNuevo("pago2_eq");
        long id = inscripcionService.crear(capitan, dosATres, existente(equipo, jugador)).id();
        inscripcionService.confirmar(capitan, id, true);
        inscripcionService.pagar(capitan, id);

        assertThat(inscripcionService.pagar(capitan, id).estado()).isEqualTo(EstadoInscripcion.CONFIRMADA);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento WHERE inscripcion_id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void soloElCapitanPagaLaInscripcionDeEquipo() {
        long dosATres = torneoPorEquipos(2, 3);
        Long jugador = integranteNuevo("pago3_eq");
        long id = inscripcionService.crear(capitan, dosATres, existente(equipo, jugador)).id();
        inscripcionService.confirmar(capitan, id, true);

        assertError(() -> inscripcionService.pagar(jugador, id), HttpStatus.NOT_FOUND, CodigoError.INSCRIPCION_NO_ENCONTRADA);
        assertError(() -> inscripcionService.pagar(organizador, id), HttpStatus.NOT_FOUND, CodigoError.INSCRIPCION_NO_ENCONTRADA);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento WHERE inscripcion_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void siElCupoSeLlenoMientrasTantoElPagoDeEquipoSeRechazaAntesDeCobrar() {
        long dosATres = torneoPorEquipos(2, 3);
        long id = inscripcionDeEquipo(dosATres, equipo, "PENDIENTE_PAGO");
        enNomina(id, capitan, equipo);
        jdbc.update("UPDATE torneo SET cupo_maximo = 2 WHERE id = ?", dosATres);
        confirmarEquipos(dosATres, 2);

        assertError(() -> inscripcionService.pagar(capitan, id), HttpStatus.CONFLICT, CodigoError.CUPO_COMPLETO);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento WHERE inscripcion_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void siElCobroSeRechazaLaInscripcionDeEquipoSigueEnPendienteDePago() {
        long dosATres = torneoPorEquipos(2, 3);
        Long jugador = integranteNuevo("rech_eq");
        long id = inscripcionService.crear(capitan, dosATres, existente(equipo, jugador)).id();
        inscripcionService.confirmar(capitan, id, true);
        doThrow(new CobroRechazadoException("sin fondos")).when(cobroService).cobrar(any());

        assertError(() -> inscripcionService.pagar(capitan, id), HttpStatus.CONFLICT, CodigoError.COBRO_RECHAZADO);

        assertThat(inscripcionService.detalle(capitan, id).estado()).isEqualTo(EstadoInscripcion.PENDIENTE_PAGO);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento WHERE inscripcion_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void cancelarUnaPendienteDePagoDeEquipoLiberaALosJugadoresDeLaNomina() {
        long dosATres = torneoPorEquipos(2, 3);
        Long jugador = integranteNuevo("libre_eq");
        long id = inscripcionService.crear(capitan, dosATres, existente(equipo, jugador)).id();
        inscripcionService.confirmar(capitan, id, true);
        Long otroCapitan = usuario("cap6_eq").getId();
        Long otroEquipo = equipo("Los Gavilanes", otroCapitan);
        assertError(() -> inscripcionService.crear(otroCapitan, dosATres, existente(otroEquipo, jugador)), HttpStatus.CONFLICT, CodigoError.JUGADOR_YA_INSCRIPTO);

        inscripcionService.cancelar(capitan, id);

        assertThat(inscripcionService.crear(otroCapitan, dosATres, existente(otroEquipo, jugador)).nomina())
                .extracting(UsuarioPublico::id).contains(jugador);
    }

    @Test
    void lasNominasYaConfirmadasNoCambianSiDespuesCrecenLosIntegrantesDelEquipo() {
        long dosATres = torneoPorEquipos(2, 3);
        Long jugador = integranteNuevo("antes_eq");
        long id = inscripcionService.crear(capitan, dosATres, existente(equipo, jugador)).id();
        inscripcionService.confirmar(capitan, id, true);

        integranteNuevo("despues_eq");

        assertThat(inscripcionService.detalle(capitan, id).nomina()).extracting(UsuarioPublico::id)
                .containsExactlyInAnyOrder(capitan, jugador);
    }

    // --- ver y cancelar -----------------------------------------------------

    @Test
    void elCapitanElOrganizadorYLosDeLaNominaVenElBorradorConSuEquipoYSuNomina() {
        Long enNomina = integranteNuevo("nom_eq");
        long id = inscripcionService.crear(capitan, torneo, existente(equipo, enNomina)).id();

        for (Long quien : List.of(capitan, organizador, enNomina)) {
            InscripcionDetalle vista = inscripcionService.detalle(quien, id);

            assertThat(vista.id()).isEqualTo(id);
            assertThat(vista.equipo().nombre()).isEqualTo("Los Halcones");
            assertThat(vista.nomina()).extracting(UsuarioPublico::id).containsExactlyInAnyOrder(capitan, enNomina);
        }
    }

    @Test
    void unIntegranteQueNoEstaEnLaNominaNiNingunOtroUsuarioLaVen() {
        Long fueraDeLaNomina = integranteNuevo("fuera_eq");
        long id = inscripcionService.crear(capitan, torneo, existente(equipo)).id();

        assertError(() -> inscripcionService.detalle(fueraDeLaNomina, id), HttpStatus.NOT_FOUND, CodigoError.INSCRIPCION_NO_ENCONTRADA);
        assertError(() -> inscripcionService.detalle(usuario("otro_eq").getId(), id), HttpStatus.NOT_FOUND, CodigoError.INSCRIPCION_NO_ENCONTRADA);
    }

    @Test
    void darDeBajaAUnIntegranteNoLoSacaDeLaNominaDeLaInscripcion() {
        Long jugador = integranteNuevo("baja_eq");
        long id = inscripcionService.crear(capitan, torneo, existente(equipo, jugador)).id();

        jdbc.update("UPDATE usuario_equipo SET eliminado_en = now() WHERE usuario_id = ? AND equipo_id = ?", jugador, equipo);

        assertThat(inscripcionService.detalle(capitan, id).nomina()).extracting(UsuarioPublico::id)
                .containsExactlyInAnyOrder(capitan, jugador);
    }

    @Test
    void elIntegranteDadoDeBajaQueSigueEnLaNominaSigueViendoLaInscripcion() {
        Long jugador = integranteNuevo("baja2_eq");
        long id = inscripcionService.crear(capitan, torneo, existente(equipo, jugador)).id();
        jdbc.update("UPDATE usuario_equipo SET eliminado_en = now() WHERE usuario_id = ? AND equipo_id = ?", jugador, equipo);

        assertThat(inscripcionService.detalle(jugador, id).id()).isEqualTo(id);
    }

    @Test
    void elCapitanCancelaElBorradorDeEquipo() {
        long id = inscripcionService.crear(capitan, torneo, existente(equipo)).id();

        inscripcionService.cancelar(capitan, id);

        assertThat(inscripcionService.detalle(capitan, id).estado()).isEqualTo(EstadoInscripcion.CANCELADA);
    }

    @Test
    void soloElCapitanCancelaElBorradorDeEquipo() {
        Long enNomina = integranteNuevo("nom2_eq");
        long id = inscripcionService.crear(capitan, torneo, existente(equipo, enNomina)).id();

        assertError(() -> inscripcionService.cancelar(enNomina, id), HttpStatus.NOT_FOUND, CodigoError.INSCRIPCION_NO_ENCONTRADA);
        assertError(() -> inscripcionService.cancelar(organizador, id), HttpStatus.NOT_FOUND, CodigoError.INSCRIPCION_NO_ENCONTRADA);
    }

    // --- reabrir ------------------------------------------------------------

    @Test
    void reabrirUnaInscripcionDeEquipoLaDejaEnBorradorConElMismoEquipoYLaMismaNomina() {
        long dosATres = torneoPorEquipos(2, 3);
        Long jugador = integranteNuevo("reab_eq");
        long id = inscripcionService.crear(capitan, dosATres, existente(equipo, jugador)).id();
        inscripcionService.confirmar(capitan, id, true);

        InscripcionDetalle reabierta = inscripcionService.reabrir(capitan, id);

        assertThat(reabierta.estado()).isEqualTo(EstadoInscripcion.BORRADOR);
        assertThat(reabierta.reglamentoAceptadoEn()).isNull();
        assertThat(reabierta.equipo().id()).isEqualTo(equipo);
        assertThat(reabierta.nomina()).extracting(UsuarioPublico::id).containsExactlyInAnyOrder(capitan, jugador);
        assertThat(jdbc.queryForList("SELECT usuario_id FROM nomina WHERE inscripcion_id = ?", Long.class, id))
                .containsExactlyInAnyOrder(capitan, jugador);
    }

    @Test
    void soloElCapitanReabreLaInscripcionDeEquipo() {
        long dosATres = torneoPorEquipos(2, 3);
        Long enNomina = integranteNuevo("reab2_eq");
        long id = inscripcionService.crear(capitan, dosATres, existente(equipo, enNomina)).id();
        inscripcionService.confirmar(capitan, id, true);

        assertError(() -> inscripcionService.reabrir(enNomina, id), HttpStatus.NOT_FOUND, CodigoError.INSCRIPCION_NO_ENCONTRADA);
        assertError(() -> inscripcionService.reabrir(organizador, id), HttpStatus.NOT_FOUND, CodigoError.INSCRIPCION_NO_ENCONTRADA);
        assertThat(inscripcionService.detalle(capitan, id).estado()).isEqualTo(EstadoInscripcion.PENDIENTE_PAGO);
    }

    @Test
    void despuesDeReabrirSeEditaLaNominaYSeConfirmaDeNuevo() {
        long dosATres = torneoPorEquipos(2, 3);
        Long primero = integranteNuevo("reab3a_eq");
        Long segundo = integranteNuevo("reab3b_eq");
        long id = inscripcionService.crear(capitan, dosATres, existente(equipo, primero)).id();
        inscripcionService.confirmar(capitan, id, true);

        inscripcionService.reabrir(capitan, id);
        inscripcionService.editar(capitan, id, existente(equipo, segundo));
        InscripcionDetalle confirmada = inscripcionService.confirmar(capitan, id, true);

        assertThat(confirmada.estado()).isEqualTo(EstadoInscripcion.PENDIENTE_PAGO);
        assertThat(confirmada.reglamentoAceptadoEn()).isNotNull();
        assertThat(confirmada.nomina()).extracting(UsuarioPublico::id).containsExactlyInAnyOrder(capitan, segundo);
    }

    // --- escenario ----------------------------------------------------------

    private void assertError(ThrowingCallable accion, HttpStatus status, CodigoError codigo) {
        ApiException error = catchThrowableOfType(ApiException.class, accion);
        assertThat(error).isNotNull();
        assertThat(error.getStatus()).isEqualTo(status);
        assertThat(error.getCodigo()).isEqualTo(codigo);
    }

    // inscripciones de equipo ya confirmadas, de otros capitanes, que ocupan lugares del torneo
    private void confirmarEquipos(long torneoId, int cantidad) {
        for (int i = 0; i < cantidad; i++) {
            Long otroCapitan = usuario("cap_lleno_" + torneoId + "_" + i).getId();
            Long otroEquipo = equipo("Lleno " + torneoId + " " + i, otroCapitan);
            enNomina(inscripcionDeEquipo(torneoId, otroEquipo, "CONFIRMADA"), otroCapitan, otroEquipo);
        }
    }

    // una inscripción de otro equipo en el mismo torneo, con el jugador en su nómina
    private void inscripcionActivaDeOtroEquipoCon(Long jugador, String estado) {
        Long otroCapitan = usuario("capx_" + estado.toLowerCase() + "_" + jugador).getId();
        Long otroEquipo = equipo("Rival " + estado + jugador, otroCapitan);
        integrante(otroEquipo, jugador);
        long inscripcion = inscripcionDeEquipo(torneo, otroEquipo, estado);
        enNomina(inscripcion, otroCapitan, otroEquipo);
        enNomina(inscripcion, jugador, otroEquipo);
    }

    private Long integranteNuevo(String nombre) {
        Long id = usuario(nombre).getId();
        integrante(equipo, id);
        return id;
    }

    private boolean integranteActivo(Long usuarioId, long equipoId) {
        return jdbc.queryForObject("SELECT count(*) FROM usuario_equipo WHERE usuario_id = ? AND equipo_id = ? AND eliminado_en IS NULL",
                Integer.class, usuarioId, equipoId) == 1;
    }

    private InscripcionEquipoRequest existente(long equipoId, Long... nomina) {
        return new InscripcionEquipoRequest(equipoId, null, List.of(nomina));
    }

    private InscripcionEquipoRequest nuevo(String nombre, Long... nomina) {
        return new InscripcionEquipoRequest(null, nombre, List.of(nomina));
    }

    private long torneoPorEquipos(int tamanio, Integer nominaMaxima) {
        return jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, tamanio_equipo, nomina_maxima,
                                    valor_inscripcion, pct_pozo_competidores, pct_premio_rifa, cierre_inscripcion_en,
                                    fecha_inicio, estado)
                VALUES (?, 'Torneo', 'https://r', 'Futbol', 'ELIMINACION_DIRECTA', 'PRESENCIAL', 'EQUIPO', 8, 2, ?, ?,
                        1000, 50, 10, now() + interval '1 day', now() + interval '7 days', 'INSCRIPCION_ABIERTA')
                RETURNING id
                """, Long.class, organizador, tamanio, nominaMaxima);
    }

    private long equipo(String nombre, Long capitanId) {
        long id = jdbc.queryForObject("INSERT INTO equipo (capitan_id, nombre) VALUES (?, ?) RETURNING id", Long.class, capitanId, nombre);
        integrante(id, capitanId);
        return id;
    }

    private void integrante(long equipoId, Long usuarioId) {
        jdbc.update("INSERT INTO usuario_equipo (usuario_id, equipo_id) VALUES (?, ?)", usuarioId, equipoId);
    }

    private long inscripcionDeEquipo(long torneoId, long equipoId, String estado) {
        return jdbc.queryForObject("""
                INSERT INTO inscripcion (torneo_id, equipo_id, estado, reglamento_aceptado_en)
                VALUES (?, ?, ?, CASE WHEN ? = 'BORRADOR' THEN NULL ELSE now() END)
                RETURNING id
                """, Long.class, torneoId, equipoId, estado, estado);
    }

    private void enNomina(long inscripcionId, Long usuarioId, long equipoId) {
        jdbc.update("INSERT INTO nomina (inscripcion_id, usuario_id, equipo_id) VALUES (?, ?, ?)", inscripcionId, usuarioId, equipoId);
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
