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
        TorneoInformacion cerrado = inscripcionTorneoService.cerrar(organizador, torneo);

        assertError(() -> inscripcionTorneoService.cerrar(organizador, torneo), HttpStatus.CONFLICT, CodigoError.INSCRIPCION_NO_ABIERTA);
        assertThat(jdbc.queryForObject("SELECT lista_hash FROM torneo WHERE id = ?", String.class, torneo))
                .isEqualTo(cerrado.listaHash());
    }

    @Test
    void conLaInscripcionCerradaNoSeCambiaElCupoMaximo() {
        TorneoInformacion cerrado = inscripcionTorneoService.cerrar(organizador, torneo);

        assertThat(cerrado.camposBloqueados()).contains("cupoMaximo", "cierreInscripcionEn");
    }

    // --- después del cierre --------------------------------------------------

    @Test
    void despuesDelCierreNadieSeInscribeNiPagaLaQueQuedoPendiente() {
        long individual = torneo("INDIVIDUAL", 1000);
        Long pendiente = usuario("post_pendiente").getId();
        long id = inscripcionIndividual(individual, pendiente, "PENDIENTE_PAGO");
        Long nuevo = usuario("post_nuevo").getId();

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
    void sinConfirmadasElSelloEsElDeLaListaVacia() {
        TorneoInformacion cerrado = inscripcionTorneoService.cerrar(organizador, torneo);

        assertThat(cerrado.listaHash()).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

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

        TorneoInformacion cerrado = inscripcionTorneoService.cerrar(organizador, torneo);

        assertThat(cerrado.listaHash()).isEqualTo(sha256(inscripcion + ";E;" + equipo + ";" + menor + "," + mayor));
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

        inscripcionTorneoService.cerrar(organizador, torneo);

        assertThat(estadoDe(pendienteDeOtro)).isEqualTo("PENDIENTE_PAGO");
    }

    // --- auditoría -------------------------------------------------------------

    @Test
    void elCierreQuedaEnLaAuditoriaConElMotivoYCuantasSeCancelaron() {
        long individual = torneo("INDIVIDUAL", 1000);
        inscripcionIndividual(individual, usuario("aud_borrador").getId(), "BORRADOR");
        inscripcionIndividual(individual, usuario("aud_pendiente").getId(), "PENDIENTE_PAGO");

        inscripcionTorneoService.cerrar(organizador, individual);

        Map<String, Object> fila = jdbc.queryForMap("""
                SELECT usuario_id, entidad, detalle->>'motivo' AS motivo, (detalle->>'canceladas')::int AS canceladas
                FROM auditoria WHERE accion = 'CIERRE_INSCRIPCION' AND entidad_id = ?
                """, String.valueOf(individual));
        assertThat(fila).containsEntry("usuario_id", organizador).containsEntry("entidad", "TORNEO")
                .containsEntry("motivo", "MANUAL").containsEntry("canceladas", 2);
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

    private void assertError(ThrowingCallable accion, HttpStatus status, CodigoError codigo) {
        ApiException error = catchThrowableOfType(ApiException.class, accion);
        assertThat(error).isNotNull();
        assertThat(error.getStatus()).isEqualTo(status);
        assertThat(error.getCodigo()).isEqualTo(codigo);
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
