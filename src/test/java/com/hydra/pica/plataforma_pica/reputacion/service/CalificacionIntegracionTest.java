package com.hydra.pica.plataforma_pica.reputacion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import java.util.Map;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionDelTorneo;
import com.hydra.pica.plataforma_pica.partido.service.TorneoConGruposBase;
import com.hydra.pica.plataforma_pica.reputacion.dto.CalificacionDetalle;
import com.hydra.pica.plataforma_pica.reputacion.dto.CalificacionRequest;
import com.hydra.pica.plataforma_pica.reputacion.dto.JugadorCalificable;
import com.hydra.pica.plataforma_pica.reputacion.dto.Reputacion;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Formato;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.dto.InformacionTorneoRequest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Las pruebas de aceptación de HU-805 (T805-1, PICA-427) sobre un torneo del organizador con jugadores confirmados,
 * que se da por terminado cambiándole el estado.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class CalificacionIntegracionTest extends TorneoConGruposBase {

    @Autowired private CalificacionService calificacionService;

    @Test
    void aUnJugadorQueNuncaParticipoIndicaError() {
        inscribir(1);
        estado(EstadoTorneo.FINALIZADO);
        Long ajeno = usuario("no_jugo", "PARTICIPANTE").getId();

        error(() -> calificacionService.calificar(organizador, torneo, new CalificacionRequest(ajeno, 4, null, false)),
                HttpStatus.CONFLICT, CodigoError.JUGADOR_NO_PARTICIPA);
        assertThat(cantidadDeCalificaciones()).isZero();
    }

    @Test
    void dosVecesAlMismoJugadorEnElMismoTorneoIndicaError() {
        Long jugador = jugadorDe(inscribir(1).getFirst());
        estado(EstadoTorneo.FINALIZADO);
        calificacionService.calificar(organizador, torneo, new CalificacionRequest(jugador, 5, null, false));

        error(() -> calificacionService.calificar(organizador, torneo, new CalificacionRequest(jugador, 2, null, true)),
                HttpStatus.CONFLICT, CodigoError.JUGADOR_YA_CALIFICADO);
        assertThat(cantidadDeCalificaciones()).isEqualTo(1);
    }

    @Test
    void laMarcaNoConfiableLaVenLosOrganizadoresEnLasInscripcionesYNoBloqueaAlJugador() {
        List<Long> inscripciones = inscribir(2);
        Long marcado = jugadorDe(inscripciones.get(0));
        Long otro = jugadorDe(inscripciones.get(1));
        estado(EstadoTorneo.FINALIZADO);

        calificacionService.calificar(organizador, torneo,
                new CalificacionRequest(marcado, 1, "No se presentó a dos partidos", true));
        entityManager.flush();
        entityManager.clear();

        List<InscripcionDelTorneo> filas = inscripcionTorneoService
                .listar(organizador, torneo, null, PageRequest.of(0, 20)).getContent();
        assertThat(filas).filteredOn(i -> i.usuario().id().equals(marcado))
                .singleElement().extracting(InscripcionDelTorneo::noConfiable).isEqualTo(true);
        assertThat(filas).filteredOn(i -> i.usuario().id().equals(otro))
                .singleElement().extracting(InscripcionDelTorneo::noConfiable).isEqualTo(false);
        // la ve también otro organizador cuando el jugador se inscribe en un torneo suyo
        Long otroOrganizador = usuario("org_nuevo", "ORGANIZADOR").getId();
        Long torneoNuevo = torneoCon(otroOrganizador, marcado, EstadoTorneo.INSCRIPCION_ABIERTA);
        assertThat(inscripcionTorneoService.listar(otroOrganizador, torneoNuevo, null, PageRequest.of(0, 20))
                .getContent()).singleElement().extracting(InscripcionDelTorneo::noConfiable).isEqualTo(true);
        // no lo bloquea: sigue activo y con su inscripción
        assertThat(jdbc.queryForObject("SELECT estado FROM usuario WHERE id = ?", String.class, marcado))
                .isEqualTo("ACTIVO");
        assertThat(jdbc.queryForObject("SELECT estado FROM inscripcion WHERE id = ?", String.class,
                inscripciones.get(0))).isEqualTo("CONFIRMADA");
    }

    @Test
    void unaCalificacionValidaQuedaRegistradaYSeReflejaEnElPuntaje() {
        List<Long> inscripciones = inscribir(2);
        Long jugador = jugadorDe(inscripciones.get(0));
        jdbc.update("UPDATE torneo SET motivo_cancelacion = 'Lluvia' WHERE id = ?", torneo);
        estado(EstadoTorneo.CANCELADO);

        CalificacionDetalle calificacion = calificacionService.calificar(organizador, torneo,
                new CalificacionRequest(jugador, 4, "  Puntual y respetuoso  ", false));

        assertThat(calificacion.torneoId()).isEqualTo(torneo);
        assertThat(calificacion.organizador().id()).isEqualTo(organizador);
        assertThat(calificacion.jugador().id()).isEqualTo(jugador);
        assertThat(calificacion.puntaje()).isEqualTo(4);
        assertThat(calificacion.comentario()).isEqualTo("Puntual y respetuoso");
        assertThat(calificacion.noConfiable()).isFalse();
        assertThat(calificacion.creadaEn()).isNotNull();

        Map<String, Object> auditoria = jdbc.queryForMap(
                "SELECT usuario_id, entidad, entidad_id, detalle::text AS detalle FROM auditoria WHERE accion = 'CALIFICACION'");
        assertThat(auditoria.get("usuario_id")).isEqualTo(organizador);
        assertThat(auditoria.get("entidad")).isEqualTo("TORNEO");
        assertThat(auditoria.get("entidad_id")).isEqualTo(torneo.toString());
        assertThat(auditoria.get("detalle").toString()).contains("Puntual y respetuoso");

        // otra calificación, en otro torneo terminado del mismo organizador, mueve el promedio
        calificacionService.calificar(organizador, torneoCon(organizador, jugador, EstadoTorneo.FINALIZADO),
                new CalificacionRequest(jugador, 1, null, false));
        entityManager.flush();
        entityManager.clear();
        Long sinCalificar = jugadorDe(inscripciones.get(1));
        Map<Long, Reputacion> reputaciones = calificacionService.reputaciones(List.of(jugador, sinCalificar));
        Reputacion esperada = new Reputacion(2.5, 2);
        assertThat(reputaciones.get(jugador)).isEqualTo(esperada);
        assertThat(reputaciones.get(sinCalificar)).isEqualTo(Reputacion.SIN_CALIFICACIONES);
        assertThat(inscripcionTorneoService.listar(organizador, torneo, null, PageRequest.of(0, 20)).getContent())
                .filteredOn(i -> i.usuario().id().equals(jugador))
                .singleElement().extracting(InscripcionDelTorneo::reputacion).isEqualTo(esperada);
    }

    @Test
    void soloElOrganizadorYConElTorneoTerminado() {
        Long jugador = jugadorDe(inscribir(1).getFirst());
        CalificacionRequest request = new CalificacionRequest(jugador, 3, null, false);

        error(() -> calificacionService.calificar(organizador, torneo, request), HttpStatus.CONFLICT,
                CodigoError.TORNEO_NO_TERMINADO);
        estado(EstadoTorneo.FINALIZADO);
        error(() -> calificacionService.calificar(arbitro, torneo, request), HttpStatus.FORBIDDEN,
                CodigoError.SIN_PERMISO);
        error(() -> calificacionService.calificables(jugador, torneo), HttpStatus.FORBIDDEN, CodigoError.SIN_PERMISO);
        assertThat(cantidadDeCalificaciones()).isZero();
    }

    @Test
    void losCalificablesTraenLaCalificacionDeLosYaCalificados() {
        List<Long> inscripciones = inscribir(2);
        Long calificado = jugadorDe(inscripciones.get(0));
        estado(EstadoTorneo.FINALIZADO);
        CalificacionDetalle calificacion = calificacionService.calificar(organizador, torneo,
                new CalificacionRequest(calificado, 5, null, false));
        entityManager.flush();
        entityManager.clear();

        List<JugadorCalificable> calificables = calificacionService.calificables(organizador, torneo);

        assertThat(calificables).extracting(JugadorCalificable::inscripcionId).containsExactlyElementsOf(inscripciones);
        assertThat(calificables.get(0).calificacion().id()).isEqualTo(calificacion.id());
        assertThat(calificables.get(1).calificacion()).isNull();
    }

    private Long jugadorDe(Long inscripcion) {
        return jdbc.queryForObject("SELECT usuario_id FROM inscripcion WHERE id = ?", Long.class, inscripcion);
    }

    /** Otro torneo del organizador, en ese estado, con el jugador confirmado. */
    private Long torneoCon(Long organizadorId, Long jugador, EstadoTorneo estado) {
        Long otro = torneoService.crear(organizadorId, new InformacionTorneoRequest("Copa de invierno", "Ajedrez", null,
                Formato.GRUPOS_Y_ELIMINACION, reglas(), Modalidad.ONLINE, TipoParticipante.INDIVIDUAL, null, null,
                EN_DOS_SEMANAS, null, EN_UNA_SEMANA, "Partidas de 10 minutos", cupoMaximo(), cupoMinimo(), null, null,
                Map.of())).id();
        jdbc.update("""
                UPDATE torneo SET valor_inscripcion = 1000, pct_pozo_competidores = 90, pct_premio_rifa = 0,
                                  estado = ?, publicado_en = now()
                WHERE id = ?""", estado.name(), otro);
        jdbc.update("""
                INSERT INTO inscripcion (torneo_id, usuario_id, estado, reglamento_aceptado_en)
                VALUES (?, ?, 'CONFIRMADA', now())""", otro, jugador);
        entityManager.clear();
        return otro;
    }

    private int cantidadDeCalificaciones() {
        return jdbc.queryForObject("SELECT count(*) FROM calificacion", Integer.class);
    }

    private static ApiException error(ThrowingCallable accion, HttpStatus status, CodigoError codigo) {
        ApiException error = catchThrowableOfType(ApiException.class, accion);
        assertThat(error).isNotNull();
        assertThat(error.getStatus()).isEqualTo(status);
        assertThat(error.getCodigo()).isEqualTo(codigo);
        return error;
    }
}
