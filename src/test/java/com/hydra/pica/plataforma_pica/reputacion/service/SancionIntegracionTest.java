package com.hydra.pica.plataforma_pica.reputacion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import java.util.Map;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.partido.service.TorneoConGruposBase;
import com.hydra.pica.plataforma_pica.reputacion.domain.Gravedad;
import com.hydra.pica.plataforma_pica.reputacion.dto.SancionDetalle;
import com.hydra.pica.plataforma_pica.reputacion.dto.SancionRequest;
import com.hydra.pica.plataforma_pica.reputacion.event.SancionAplicada;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

/**
 * Las pruebas de aceptación de HU-803 (T803-1, PICA-423) sobre un torneo publicado con su organizador, un árbitro que
 * aceptó y jugadores confirmados.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
@RecordApplicationEvents
class SancionIntegracionTest extends TorneoConGruposBase {

    @Autowired private SancionService sancionService;
    @Autowired private ApplicationEvents eventos;

    @Test
    void sinMotivoIndicaError() {
        Long jugador = jugadorDe(inscribir(1).getFirst());

        ApiException error = error(() -> sancionService.aplicar(organizador, torneo,
                new SancionRequest(jugador, null, Gravedad.LEVE, "  ")), HttpStatus.BAD_REQUEST, CodigoError.VALIDACION);

        assertThat(error.getPropiedades().get("errores").toString()).contains("motivo");
        assertThat(cantidadDeSanciones()).isZero();
    }

    @Test
    void aUnJugadorQueNoParticipaIndicaError() {
        inscribir(1);
        Long ajeno = usuario("no_juega", "PARTICIPANTE").getId();

        error(() -> sancionService.aplicar(organizador, torneo, new SancionRequest(ajeno, null, Gravedad.LEVE,
                "Insultó al árbitro")), HttpStatus.CONFLICT, CodigoError.JUGADOR_NO_PARTICIPA);
        assertThat(cantidadDeSanciones()).isZero();
    }

    @Test
    void unaInscripcionSinConfirmarNoCuentaComoParticipar() {
        Long jugador = jugadorDe(inscribir(1).getFirst());
        jdbc.update("UPDATE inscripcion SET estado = 'PENDIENTE_PAGO' WHERE torneo_id = ?", torneo);

        error(() -> sancionService.aplicar(organizador, torneo, new SancionRequest(jugador, null, Gravedad.LEVE,
                "Insultó al árbitro")), HttpStatus.CONFLICT, CodigoError.JUGADOR_NO_PARTICIPA);
    }

    @Test
    void quienNoOrganizaNiArbitraNoPuedeSancionar() {
        List<Long> inscripciones = inscribir(2);
        Long jugador = jugadorDe(inscripciones.get(0));
        Long otroJugador = jugadorDe(inscripciones.get(1));
        Long arbitroSinAceptar = usuario("juez_pendiente", "ARBITRO").getId();
        jdbc.update("INSERT INTO torneo_arbitro (torneo_id, usuario_id, estado) VALUES (?, ?, 'PENDIENTE')", torneo,
                arbitroSinAceptar);
        SancionRequest request = new SancionRequest(jugador, null, Gravedad.MEDIA, "Juego brusco");

        error(() -> sancionService.aplicar(otroJugador, torneo, request), HttpStatus.FORBIDDEN, CodigoError.SIN_PERMISO);
        error(() -> sancionService.aplicar(arbitroSinAceptar, torneo, request), HttpStatus.FORBIDDEN,
                CodigoError.SIN_PERMISO);
        assertThat(cantidadDeSanciones()).isZero();
    }

    @Test
    void unaSancionValidaQuedaRegistradaEnLaAuditoriaYPublicaElEvento() {
        Long jugador = jugadorDe(inscribir(1).getFirst());

        SancionDetalle sancion = sancionService.aplicar(organizador, torneo,
                new SancionRequest(jugador, null, Gravedad.GRAVE, "  Agredió a un rival  "));

        assertThat(sancion.torneoId()).isEqualTo(torneo);
        assertThat(sancion.torneo()).isEqualTo("Copa con grupos");
        assertThat(sancion.jugador().id()).isEqualTo(jugador);
        assertThat(sancion.motivo()).isEqualTo("Agredió a un rival");
        assertThat(sancion.gravedad()).isEqualTo(Gravedad.GRAVE);
        assertThat(sancion.aplicadaPor().id()).isEqualTo(organizador);
        assertThat(sancion.aplicadaEn()).isNotNull();
        assertThat(sancion.anuladaEn()).isNull();

        Map<String, Object> auditoria = jdbc.queryForMap(
                "SELECT usuario_id, entidad, entidad_id, detalle::text AS detalle FROM auditoria WHERE accion = 'SANCION'");
        assertThat(auditoria.get("usuario_id")).isEqualTo(organizador);
        assertThat(auditoria.get("entidad")).isEqualTo("TORNEO");
        assertThat(auditoria.get("entidad_id")).isEqualTo(torneo.toString());
        assertThat(auditoria.get("detalle").toString()).contains("GRAVE", "Agredió a un rival");

        assertThat(eventos.stream(SancionAplicada.class))
                .containsExactly(new SancionAplicada(sancion.id(), torneo, jugador, Gravedad.GRAVE));
    }

    @Test
    void unArbitroQueAceptoPuedeSancionarAunqueNoTengaPermisoDeResolver() {
        Long jugador = jugadorDe(inscribir(1).getFirst());

        SancionDetalle sancion = sancionService.aplicar(arbitro, torneo,
                new SancionRequest(jugador, null, Gravedad.LEVE, "Demoró el partido"));

        assertThat(sancion.aplicadaPor().id()).isEqualTo(arbitro);
    }

    @Test
    void seSancionaAUnJugadorDeLaNominaDeUnEquipo() {
        jdbc.update("UPDATE torneo SET tipo_participante = 'EQUIPO', tamanio_equipo = 2, nomina_maxima = 3 WHERE id = ?",
                torneo);
        entityManager.clear();
        Long capitan = usuario("capitan_sancion", "PARTICIPANTE").getId();
        Long jugador = usuario("jugador_sancion", "PARTICIPANTE").getId();
        Long equipo = jdbc.queryForObject("INSERT INTO equipo (capitan_id, nombre) VALUES (?, 'Los Rudos') RETURNING id",
                Long.class, capitan);
        jdbc.update("INSERT INTO usuario_equipo (usuario_id, equipo_id) VALUES (?, ?), (?, ?)", capitan, equipo,
                jugador, equipo);
        Long inscripcion = jdbc.queryForObject("""
                INSERT INTO inscripcion (torneo_id, equipo_id, estado, reglamento_aceptado_en)
                VALUES (?, ?, 'CONFIRMADA', now()) RETURNING id""", Long.class, torneo, equipo);
        jdbc.update("INSERT INTO nomina (inscripcion_id, usuario_id, equipo_id) VALUES (?, ?, ?), (?, ?, ?)",
                inscripcion, capitan, equipo, inscripcion, jugador, equipo);

        SancionDetalle sancion = sancionService.aplicar(organizador, torneo,
                new SancionRequest(jugador, null, Gravedad.MEDIA, "Protestó cada fallo"));

        assertThat(sancion.jugador().id()).isEqualTo(jugador);
    }

    @Test
    void conPartidoTieneQueSerDelTorneoYDelJugador() {
        List<Long> inscripciones = inscribir(8);
        cerrar();
        bracketService.generar(organizador, torneo);
        entityManager.flush();
        entityManager.clear();
        Long jugador = jugadorDe(inscripciones.getFirst());
        Long suyo = partidoDe(inscripciones.getFirst(), true);
        Long ajeno = partidoDe(inscripciones.getFirst(), false);

        SancionDetalle sancion = sancionService.aplicar(organizador, torneo,
                new SancionRequest(jugador, suyo, Gravedad.LEVE, "Llegó tarde"));
        assertThat(sancion.partidoId()).isEqualTo(suyo);

        ApiException error = error(() -> sancionService.aplicar(organizador, torneo,
                new SancionRequest(jugador, ajeno, Gravedad.LEVE, "Llegó tarde")), HttpStatus.BAD_REQUEST,
                CodigoError.VALIDACION);
        assertThat(error.getPropiedades().get("errores").toString()).contains("partidoId");
    }

    @Test
    void enUnBorradorNoExisteElTorneo() {
        Long jugador = jugadorDe(inscribir(1).getFirst());
        jdbc.update("UPDATE torneo SET estado = 'BORRADOR' WHERE id = ?", torneo);
        entityManager.clear();

        error(() -> sancionService.aplicar(organizador, torneo, new SancionRequest(jugador, null, Gravedad.LEVE,
                "Insultó")), HttpStatus.NOT_FOUND, CodigoError.TORNEO_NO_ENCONTRADO);
    }

    @Test
    void lasSancionesSeVenPorTorneoYLasPropiasPorJugador() {
        List<Long> inscripciones = inscribir(2);
        Long uno = jugadorDe(inscripciones.get(0));
        Long otro = jugadorDe(inscripciones.get(1));
        SancionDetalle primera = sancionService.aplicar(organizador, torneo,
                new SancionRequest(uno, null, Gravedad.LEVE, "Primera"));
        SancionDetalle segunda = sancionService.aplicar(arbitro, torneo,
                new SancionRequest(otro, null, Gravedad.MEDIA, "Segunda"));
        SancionDetalle tercera = sancionService.aplicar(organizador, torneo,
                new SancionRequest(uno, null, Gravedad.GRAVE, "Tercera"));
        entityManager.flush();
        entityManager.clear();

        assertThat(sancionService.delTorneo(torneo)).extracting(SancionDetalle::id)
                .containsExactly(tercera.id(), segunda.id(), primera.id());
        assertThat(sancionService.mias(uno)).extracting(SancionDetalle::id)
                .containsExactly(tercera.id(), primera.id());
        assertThat(sancionService.mias(organizador)).isEmpty();
    }

    private Long jugadorDe(Long inscripcion) {
        return jdbc.queryForObject("SELECT usuario_id FROM inscripcion WHERE id = ?", Long.class, inscripcion);
    }

    /** Un partido de grupos en el que juega (o no) la inscripción. */
    private Long partidoDe(Long inscripcion, boolean juega) {
        return jdbc.queryForObject("""
                SELECT p.id FROM partido p WHERE p.torneo_id = ? AND p.fase = 'GRUPOS'
                  AND EXISTS (SELECT 1 FROM juega j WHERE j.partido_id = p.id) AND
                  (EXISTS (SELECT 1 FROM juega j WHERE j.partido_id = p.id AND j.inscripcion_id = ?)) = ?
                ORDER BY p.id LIMIT 1""", Long.class, torneo, inscripcion, juega);
    }

    private int cantidadDeSanciones() {
        return jdbc.queryForObject("SELECT count(*) FROM sancion", Integer.class);
    }

    private static ApiException error(ThrowingCallable accion, HttpStatus status, CodigoError codigo) {
        ApiException error = catchThrowableOfType(ApiException.class, accion);
        assertThat(error).isNotNull();
        assertThat(error.getStatus()).isEqualTo(status);
        assertThat(error.getCodigo()).isEqualTo(codigo);
        return error;
    }
}
