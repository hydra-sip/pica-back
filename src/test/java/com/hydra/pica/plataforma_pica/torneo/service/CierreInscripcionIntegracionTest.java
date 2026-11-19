package com.hydra.pica.plataforma_pica.torneo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionDelTorneo;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionDetalle;
import com.hydra.pica.plataforma_pica.inscripcion.dto.ReemplazoRequest;
import com.hydra.pica.plataforma_pica.inscripcion.service.InscripcionService;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoInformacion;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cierre de la inscripción (HU-206, PICA-212) contra Postgres: lo que pasa al cerrar, sea a mano, por cupo o por
 * fecha. Cada test hace rollback al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class CierreInscripcionIntegracionTest {

    @Autowired private InscripcionTorneoService inscripcionTorneoService;
    @Autowired private InscripcionService inscripcionService;
    @Autowired private TorneoService torneoService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;

    private Long organizador;
    private long torneo;

    @BeforeEach
    void escenario() {
        organizador = usuario("org_cierre").getId();
        torneo = torneo("EQUIPO", 1000);
    }

    // --- a mano -------------------------------------------------------------

    @Test
    void elOrganizadorCierraLaInscripcionYQuedaLaFechaYHoraDelCierre() {
        confirmadas(torneo, "fecha_cierre", 2);
        Instant antes = Instant.now();

        TorneoInformacion cerrado = inscripcionTorneoService.cerrar(organizador, torneo);

        assertThat(cerrado.estado()).isEqualTo(EstadoTorneo.INSCRIPCION_CERRADA);
        assertThat(cerrado.inscripcionCerradaEn()).isBetween(antes.minusSeconds(1), Instant.now().plusSeconds(1));
        assertThat(jdbc.queryForObject("SELECT estado FROM torneo WHERE id = ?", String.class, torneo))
                .isEqualTo("INSCRIPCION_CERRADA");
    }

    @Test
    void paraQuienNoOrganizaElTorneoNoExiste() {
        Long otro = usuario("otro_org_cierre").getId();

        assertError(() -> inscripcionTorneoService.cerrar(otro, torneo), HttpStatus.NOT_FOUND, CodigoError.TORNEO_NO_ENCONTRADO);
        assertThat(jdbc.queryForObject("SELECT estado FROM torneo WHERE id = ?", String.class, torneo))
                .isEqualTo("INSCRIPCION_ABIERTA");
    }

    @Test
    void unaInscripcionYaCerradaNoSeVuelveACerrarNiCambiaElSello() {
        confirmadas(torneo, "ya_cerrada", 2);
        TorneoInformacion cerrado = inscripcionTorneoService.cerrar(organizador, torneo);

        assertError(() -> inscripcionTorneoService.cerrar(organizador, torneo), HttpStatus.CONFLICT, CodigoError.INSCRIPCION_NO_ABIERTA);
        assertThat(jdbc.queryForObject("SELECT lista_hash FROM torneo WHERE id = ?", String.class, torneo))
                .isEqualTo(cerrado.listaHash());
    }

    @Test
    void conLaInscripcionCerradaNoSeCambiaElCupoMaximo() {
        confirmadas(torneo, "cupo_bloqueado", 2);
        TorneoInformacion cerrado = inscripcionTorneoService.cerrar(organizador, torneo);

        assertThat(cerrado.camposBloqueados()).contains("cupoMaximo", "cierreInscripcionEn");
    }

    // --- el mínimo para jugar (PICA-447) ------------------------------------------

    @Test
    void enEliminacionDirectaConCeroOUnaConfirmadaNoSeCierraYConDosSi() {
        long individual = torneo("INDIVIDUAL", 1000);
        // los borradores y las pendientes de pago no cuentan
        inscripcionIndividual(individual, usuario("min_borrador").getId(), "BORRADOR");
        inscripcionIndividual(individual, usuario("min_pendiente").getId(), "PENDIENTE_PAGO");

        ApiException conCero = assertError(() -> inscripcionTorneoService.cerrar(organizador, individual),
                HttpStatus.CONFLICT, CodigoError.PARTICIPANTES_INSUFICIENTES);
        confirmadas(individual, "min_uno", 1);
        ApiException conUna = assertError(() -> inscripcionTorneoService.cerrar(organizador, individual),
                HttpStatus.CONFLICT, CodigoError.PARTICIPANTES_INSUFICIENTES);

        assertThat(conCero.getMessage()).contains("tiene 0 inscripciones confirmadas", "al menos 2");
        assertThat(conUna.getMessage()).contains("tiene 1 inscripciones confirmadas", "al menos 2");
        assertThat(estadoDelTorneo(individual)).isEqualTo("INSCRIPCION_ABIERTA");
        assertThat(jdbc.queryForObject("SELECT lista_hash FROM torneo WHERE id = ?", String.class, individual)).isNull();

        confirmadas(individual, "min_dos", 1);
        assertThat(inscripcionTorneoService.cerrar(organizador, individual).estado())
                .isEqualTo(EstadoTorneo.INSCRIPCION_CERRADA);
    }

    @Test
    void conGruposPorDebajoDeGruposPorUnoMasQueLosQuePasanNoSeCierraYEnElMinimoSi() {
        // cupo 8 en grupos de 4: 2 grupos; pasan 2, así que hacen falta 2 × 3 = 6
        long conGrupos = torneoConGrupos(8, 4, 2);
        confirmadas(conGrupos, "grupos_min", 5);

        ApiException error = assertError(() -> inscripcionTorneoService.cerrar(organizador, conGrupos),
                HttpStatus.CONFLICT, CodigoError.PARTICIPANTES_INSUFICIENTES);

        assertThat(error.getMessage()).contains("tiene 5 inscripciones confirmadas", "al menos 6");
        assertThat(estadoDelTorneo(conGrupos)).isEqualTo("INSCRIPCION_ABIERTA");

        confirmadas(conGrupos, "grupos_sexta", 1);
        assertThat(inscripcionTorneoService.cerrar(organizador, conGrupos).estado())
                .isEqualTo(EstadoTorneo.INSCRIPCION_CERRADA);
    }

    @Test
    void elDetalleDelTorneoTraeElMinimoDeInscripcionesConfirmadas() {
        long individual = torneo("INDIVIDUAL", 1000);
        long conGrupos = torneoConGrupos(16, 4, 2);

        assertThat(torneoService.detalle(organizador, individual).inscriptosMinimos()).isEqualTo(2);
        assertThat(torneoService.detalle(organizador, conGrupos).inscriptosMinimos()).isEqualTo(12);
    }

    // --- después del cierre --------------------------------------------------

    @Test
    void despuesDelCierreNadieSeInscribeNiPagaLaQueQuedoPendiente() {
        long individual = torneo("INDIVIDUAL", 1000);
        Long pendiente = usuario("post_pendiente").getId();
        long id = inscripcionIndividual(individual, pendiente, "PENDIENTE_PAGO");
        Long nuevo = usuario("post_nuevo").getId();
        confirmadas(individual, "post_conf", 2);

        inscripcionTorneoService.cerrar(organizador, individual);

        assertError(() -> inscripcionService.crear(nuevo, individual, null), HttpStatus.CONFLICT, CodigoError.INSCRIPCION_NO_ABIERTA);
        assertError(() -> inscripcionService.pagar(pendiente, id), HttpStatus.CONFLICT, CodigoError.INSCRIPCION_NO_ABIERTA);
    }

    @Test
    void despuesDelCierreNoSeReemplazaUnJugador() {
        Long capitan = usuario("cap_post_cierre").getId();
        Long sale = usuario("sale_post_cierre").getId();
        Long entra = usuario("entra_post_cierre").getId();
        long equipo = equipo("Los Tardíos", capitan);
        integrante(equipo, sale);
        long id = inscripcionDeEquipo(torneo, equipo, "CONFIRMADA");
        enNomina(id, capitan, equipo);
        enNomina(id, sale, equipo);
        confirmadas(torneo, "post_reemplazo", 1);

        inscripcionTorneoService.cerrar(organizador, torneo);

        assertError(() -> inscripcionService.reemplazar(capitan, id, new ReemplazoRequest(sale, entra)),
                HttpStatus.CONFLICT, CodigoError.INSCRIPCION_NO_ABIERTA);
    }

    // --- por cupo --------------------------------------------------------------

    @Test
    void elPagoQueCompletaElCupoCierraLaInscripcionEnEseMomento() {
        long individual = torneo("INDIVIDUAL", 1000, 2);
        inscripcionIndividual(individual, usuario("cupo_ya_confirmada").getId(), "CONFIRMADA");
        Long ultimo = usuario("cupo_ultimo").getId();
        long id = inscripcionIndividual(individual, ultimo, "PENDIENTE_PAGO");
        long borrador = inscripcionIndividual(individual, usuario("cupo_borrador").getId(), "BORRADOR");

        InscripcionDetalle pagada = inscripcionService.pagar(ultimo, id);

        assertThat(pagada.estado()).isEqualTo(EstadoInscripcion.CONFIRMADA);
        assertThat(estadoDelTorneo(individual)).isEqualTo("INSCRIPCION_CERRADA");
        assertThat(estadoDe(borrador)).isEqualTo("CANCELADA");
        assertThat(jdbc.queryForObject("SELECT lista_hash IS NOT NULL FROM torneo WHERE id = ?", Boolean.class, individual)).isTrue();
        assertThat(auditoriaDelCierre(individual)).containsEntry("motivo", "CUPO").containsEntry("usuario_id", null);
    }

    @Test
    void enUnTorneoGratisLaConfirmacionQueCompletaElCupoCierraLaInscripcion() {
        long gratis = torneo("INDIVIDUAL", 0, 2);
        inscripcionIndividual(gratis, usuario("gratis_ya_confirmada").getId(), "CONFIRMADA");
        Long ultimo = usuario("gratis_ultimo").getId();
        long id = inscripcionIndividual(gratis, ultimo, "BORRADOR");

        assertThat(inscripcionService.confirmar(ultimo, id, true).estado()).isEqualTo(EstadoInscripcion.CONFIRMADA);

        assertThat(estadoDelTorneo(gratis)).isEqualTo("INSCRIPCION_CERRADA");
        assertThat(auditoriaDelCierre(gratis)).containsEntry("motivo", "CUPO");
    }

    @Test
    void unPagoQueNoCompletaElCupoDejaLaInscripcionAbierta() {
        long individual = torneo("INDIVIDUAL", 1000, 2);
        Long primero = usuario("cupo_primero").getId();
        long id = inscripcionIndividual(individual, primero, "PENDIENTE_PAGO");
        inscripcionIndividual(individual, usuario("cupo_otra_pendiente").getId(), "PENDIENTE_PAGO");

        inscripcionService.pagar(primero, id);

        assertThat(estadoDelTorneo(individual)).isEqualTo("INSCRIPCION_ABIERTA");
    }

    // --- sello de la lista ---------------------------------------------------

    @Test
    void elSelloDeUnaInscripcionDeEquipoLlevaElEquipoYLaNominaOrdenada() {
        Long capitan = usuario("cap_sello").getId();
        Long jugador = usuario("jug_sello").getId();
        long equipo = equipo("Los Sellados", capitan);
        integrante(equipo, jugador);
        long inscripcion = inscripcionDeEquipo(torneo, equipo, "CONFIRMADA");
        enNomina(inscripcion, jugador, equipo);
        enNomina(inscripcion, capitan, equipo);
        long menor = Math.min(capitan, jugador);
        long mayor = Math.max(capitan, jugador);
        // el mínimo para cerrar: otro equipo, con su capitán solo
        Long otroCapitan = usuario("cap_sello_2").getId();
        long otroEquipo = equipo("Los Otros", otroCapitan);
        long otra = inscripcionDeEquipo(torneo, otroEquipo, "CONFIRMADA");
        enNomina(otra, otroCapitan, otroEquipo);

        TorneoInformacion cerrado = inscripcionTorneoService.cerrar(organizador, torneo);

        assertThat(cerrado.listaHash()).isEqualTo(sha256(inscripcion + ";E;" + equipo + ";" + menor + "," + mayor
                + "\n" + otra + ";E;" + otroEquipo + ";" + otroCapitan));
    }

    @Test
    void elSelloDeUnEquipoConRepartoLlevaElPorcentajeDeCadaIntegrante() {
        Long capitan = usuario("cap_sello_rep").getId();
        Long jugador = usuario("jug_sello_rep").getId();
        long equipo = equipo("Los Repartidos", capitan);
        integrante(equipo, jugador);
        long inscripcion = inscripcionDeEquipo(torneo, equipo, "CONFIRMADA");
        enNominaConPct(inscripcion, jugador, equipo, "35.50");
        enNominaConPct(inscripcion, capitan, equipo, "64.50");
        String menor = capitan < jugador ? capitan + ":64.50" : jugador + ":35.50";
        String mayor = capitan < jugador ? jugador + ":35.50" : capitan + ":64.50";
        // el mínimo para cerrar: otro equipo sin reparto, que conserva la línea de antes
        Long otroCapitan = usuario("cap_sello_rep_2").getId();
        long otroEquipo = equipo("Los Otros Repartidos", otroCapitan);
        long otra = inscripcionDeEquipo(torneo, otroEquipo, "CONFIRMADA");
        enNomina(otra, otroCapitan, otroEquipo);

        TorneoInformacion cerrado = inscripcionTorneoService.cerrar(organizador, torneo);

        assertThat(cerrado.listaHash()).isEqualTo(sha256(inscripcion + ";E;" + equipo + ";" + menor + "," + mayor
                + "\n" + otra + ";E;" + otroEquipo + ";" + otroCapitan));
    }

    @Test
    void elSelloLlevaSoloLasConfirmadasDeUnTorneoIndividualOrdenadasPorId() {
        long individual = torneo("INDIVIDUAL", 1000);
        Long primero = usuario("ind_sello_1").getId();
        Long segundo = usuario("ind_sello_2").getId();
        Long pendiente = usuario("ind_sello_3").getId();
        long a = inscripcionIndividual(individual, primero, "CONFIRMADA");
        long b = inscripcionIndividual(individual, segundo, "CONFIRMADA");
        inscripcionIndividual(individual, pendiente, "PENDIENTE_PAGO");

        TorneoInformacion cerrado = inscripcionTorneoService.cerrar(organizador, individual);

        assertThat(cerrado.listaHash()).isEqualTo(sha256(a + ";U;" + primero + "\n" + b + ";U;" + segundo));
    }

    // --- lo que queda en curso ----------------------------------------------

    @Test
    void alCerrarSeCancelanLosBorradoresYLasPendientesDePagoYLasConfirmadasNoCambian() {
        long individual = torneo("INDIVIDUAL", 1000);
        long borrador = inscripcionIndividual(individual, usuario("ind_borrador").getId(), "BORRADOR");
        long pendiente = inscripcionIndividual(individual, usuario("ind_pendiente").getId(), "PENDIENTE_PAGO");
        long confirmada = inscripcionIndividual(individual, usuario("ind_confirmada").getId(), "CONFIRMADA");
        long cancelada = inscripcionIndividual(individual, usuario("ind_cancelada").getId(), "CANCELADA");
        confirmadas(individual, "ind_otra", 1);

        inscripcionTorneoService.cerrar(organizador, individual);

        assertThat(estadoDe(borrador)).isEqualTo("CANCELADA");
        assertThat(estadoDe(pendiente)).isEqualTo("CANCELADA");
        assertThat(estadoDe(confirmada)).isEqualTo("CONFIRMADA");
        assertThat(estadoDe(cancelada)).isEqualTo("CANCELADA");
    }

    @Test
    void lasInscripcionesDeOtroTorneoNoSeTocan() {
        long otro = torneo("INDIVIDUAL", 1000);
        long pendienteDeOtro = inscripcionIndividual(otro, usuario("ind_otro_torneo").getId(), "PENDIENTE_PAGO");
        confirmadas(torneo, "otro_torneo", 2);

        inscripcionTorneoService.cerrar(organizador, torneo);

        assertThat(estadoDe(pendienteDeOtro)).isEqualTo("PENDIENTE_PAGO");
    }

    // --- auditoría -------------------------------------------------------------

    @Test
    void elCierreQuedaEnLaAuditoriaConElMotivoYCuantasSeCancelaron() {
        long individual = torneo("INDIVIDUAL", 1000);
        inscripcionIndividual(individual, usuario("aud_borrador").getId(), "BORRADOR");
        inscripcionIndividual(individual, usuario("aud_pendiente").getId(), "PENDIENTE_PAGO");
        confirmadas(individual, "aud_conf", 2);

        inscripcionTorneoService.cerrar(organizador, individual);

        Map<String, Object> fila = jdbc.queryForMap("""
                SELECT usuario_id, entidad, detalle->>'motivo' AS motivo, (detalle->>'canceladas')::int AS canceladas
                FROM auditoria WHERE accion = 'CIERRE_INSCRIPCION' AND entidad_id = ?
                """, String.valueOf(individual));
        assertThat(fila).containsEntry("usuario_id", organizador).containsEntry("entidad", "TORNEO")
                .containsEntry("motivo", "MANUAL").containsEntry("canceladas", 2);
    }

    // --- inscripciones del torneo (PICA-214) -----------------------------------

    @Test
    void elOrganizadorVeLasInscripcionesDeSuTorneoSinLosBorradoresYEnOrdenDeLlegada() {
        long individual = torneo("INDIVIDUAL", 1000);
        Long primero = usuario("lista_primero").getId();
        long confirmada = inscripcionIndividual(individual, primero, "CONFIRMADA");
        inscripcionIndividual(individual, usuario("lista_borrador").getId(), "BORRADOR");
        long pendiente = inscripcionIndividual(individual, usuario("lista_pendiente").getId(), "PENDIENTE_PAGO");
        long cancelada = inscripcionIndividual(individual, usuario("lista_cancelada").getId(), "CANCELADA");

        Page<InscripcionDelTorneo> pagina = inscripcionTorneoService.listar(organizador, individual, null, PageRequest.of(0, 20));

        assertThat(pagina.getContent()).extracting(InscripcionDelTorneo::id).containsExactly(confirmada, pendiente, cancelada);
        InscripcionDelTorneo fila = pagina.getContent().get(0);
        assertThat(fila.estado()).isEqualTo(EstadoInscripcion.CONFIRMADA);
        assertThat(fila.usuario().id()).isEqualTo(primero);
        assertThat(fila.usuario().username()).isEqualTo("lista_primero");
        assertThat(fila.equipo()).isNull();
        assertThat(fila.capitan()).isNull();
        assertThat(fila.jugadores()).isNull();
    }

    @Test
    void unaInscripcionDeEquipoTraeElEquipoElCapitanYCuantosHayEnLaNomina() {
        Long capitan = usuario("lista_cap").getId();
        Long jugador = usuario("lista_jug").getId();
        long equipo = equipo("Los Listados", capitan);
        integrante(equipo, jugador);
        long id = inscripcionDeEquipo(torneo, equipo, "PENDIENTE_PAGO");
        enNomina(id, capitan, equipo);
        enNomina(id, jugador, equipo);

        InscripcionDelTorneo fila = inscripcionTorneoService.listar(organizador, torneo, null, PageRequest.of(0, 20))
                .getContent().get(0);

        assertThat(fila.equipo().id()).isEqualTo(equipo);
        assertThat(fila.equipo().nombre()).isEqualTo("Los Listados");
        assertThat(fila.capitan().id()).isEqualTo(capitan);
        assertThat(fila.jugadores()).isEqualTo(2);
        assertThat(fila.usuario()).isNull();
    }

    @Test
    void conElFiltroTraeSoloLasDeEseEstadoYPaginado() {
        long individual = torneo("INDIVIDUAL", 1000);
        long a = inscripcionIndividual(individual, usuario("filtro_a").getId(), "CONFIRMADA");
        inscripcionIndividual(individual, usuario("filtro_b").getId(), "PENDIENTE_PAGO");
        long c = inscripcionIndividual(individual, usuario("filtro_c").getId(), "CONFIRMADA");
        inscripcionIndividual(individual, usuario("filtro_d").getId(), "BORRADOR");

        Page<InscripcionDelTorneo> confirmadas = inscripcionTorneoService.listar(organizador, individual,
                EstadoInscripcion.CONFIRMADA, PageRequest.of(0, 1));

        assertThat(confirmadas.getContent()).extracting(InscripcionDelTorneo::id).containsExactly(a);
        assertThat(confirmadas.getTotalElements()).isEqualTo(2);
        assertThat(inscripcionTorneoService.listar(organizador, individual, EstadoInscripcion.CONFIRMADA, PageRequest.of(1, 1))
                .getContent()).extracting(InscripcionDelTorneo::id).containsExactly(c);
        assertThat(inscripcionTorneoService.listar(organizador, individual, EstadoInscripcion.BORRADOR, PageRequest.of(0, 20))
                .getContent()).isEmpty();
    }

    @Test
    void paraQuienNoOrganizaElTorneoSusInscripcionesNoExisten() {
        Long otro = usuario("otro_lista").getId();

        assertError(() -> inscripcionTorneoService.listar(otro, torneo, null, PageRequest.of(0, 20)),
                HttpStatus.NOT_FOUND, CodigoError.TORNEO_NO_ENCONTRADO);
    }

    // --- ayudas -------------------------------------------------------------

    private String estadoDelTorneo(long torneoId) {
        return jdbc.queryForObject("SELECT estado FROM torneo WHERE id = ?", String.class, torneoId);
    }

    private Map<String, Object> auditoriaDelCierre(long torneoId) {
        return jdbc.queryForMap("""
                SELECT usuario_id, detalle->>'motivo' AS motivo FROM auditoria
                WHERE accion = 'CIERRE_INSCRIPCION' AND entidad_id = ?
                """, String.valueOf(torneoId));
    }

    private String estadoDe(long inscripcionId) {
        return jdbc.queryForObject("SELECT estado FROM inscripcion WHERE id = ?", String.class, inscripcionId);
    }

    // el formato de la spec, calculado acá sin pasar por el código que sella
    private static String sha256(String texto) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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

    private long inscripcionIndividual(long torneoId, Long usuarioId, String estado) {
        return jdbc.queryForObject("""
                INSERT INTO inscripcion (torneo_id, usuario_id, estado, reglamento_aceptado_en)
                VALUES (?, ?, ?, CASE WHEN ? = 'BORRADOR' THEN NULL ELSE now() END)
                RETURNING id
                """, Long.class, torneoId, usuarioId, estado, estado);
    }

    private void enNomina(long inscripcionId, Long usuarioId, long equipoId) {
        jdbc.update("INSERT INTO nomina (inscripcion_id, usuario_id, equipo_id) VALUES (?, ?, ?)", inscripcionId, usuarioId, equipoId);
    }

    private void enNominaConPct(long inscripcionId, Long usuarioId, long equipoId, String pct) {
        jdbc.update("INSERT INTO nomina (inscripcion_id, usuario_id, equipo_id, pct_premio) VALUES (?, ?, ?, ?)",
                inscripcionId, usuarioId, equipoId, new java.math.BigDecimal(pct));
    }

    private ApiException assertError(ThrowingCallable accion, HttpStatus status, CodigoError codigo) {
        ApiException error = catchThrowableOfType(ApiException.class, accion);
        assertThat(error).isNotNull();
        assertThat(error.getStatus()).isEqualTo(status);
        assertThat(error.getCodigo()).isEqualTo(codigo);
        return error;
    }

    /**
     * Suma {@code cuantas} inscripciones confirmadas: individuales o, si el torneo es de equipos, de un equipo con su
     * capitán solo en la nómina.
     */
    private void confirmadas(long torneoId, String prefijo, int cuantas) {
        boolean deEquipos = "EQUIPO".equals(jdbc.queryForObject(
                "SELECT tipo_participante FROM torneo WHERE id = ?", String.class, torneoId));
        for (int i = 0; i < cuantas; i++) {
            Long usuario = usuario(prefijo + i).getId();
            if (deEquipos) {
                long equipo = equipo("Equipo " + prefijo + i, usuario);
                enNomina(inscripcionDeEquipo(torneoId, equipo, "CONFIRMADA"), usuario, equipo);
            } else {
                inscripcionIndividual(torneoId, usuario, "CONFIRMADA");
            }
        }
    }

    private long torneoConGrupos(int cupoMaximo, int tamanioGrupo, int pasan) {
        long id = torneo("INDIVIDUAL", 1000, cupoMaximo);
        jdbc.update("""
                UPDATE torneo SET formato = 'GRUPOS_Y_ELIMINACION', tamanio_grupo = ?, clasifican_por_grupo = ?,
                                  puntos_victoria = 3, puntos_empate = 1
                WHERE id = ?""", tamanioGrupo, pasan, id);
        return id;
    }

    private long torneo(String tipoParticipante, int valorInscripcion) {
        return torneo(tipoParticipante, valorInscripcion, 8);
    }

    private long torneo(String tipoParticipante, int valorInscripcion, int cupoMaximo) {
        return jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, tamanio_equipo, nomina_maxima,
                                    valor_inscripcion, pct_pozo_competidores, pct_premio_rifa, cierre_inscripcion_en,
                                    fecha_inicio, estado)
                VALUES (?, 'Torneo', 'https://r', 'Futbol', 'ELIMINACION_DIRECTA', 'PRESENCIAL', ?, ?, 2,
                        CASE WHEN ? = 'EQUIPO' THEN 1 END, CASE WHEN ? = 'EQUIPO' THEN 5 END,
                        ?, 50, 10, now() + interval '1 day', now() + interval '7 days', 'INSCRIPCION_ABIERTA')
                RETURNING id
                """, Long.class, organizador, tipoParticipante, cupoMaximo, tipoParticipante, tipoParticipante,
                valorInscripcion);
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
