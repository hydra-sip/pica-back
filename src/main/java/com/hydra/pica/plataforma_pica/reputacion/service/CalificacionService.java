package com.hydra.pica.plataforma_pica.reputacion.service;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.error.ProhibidoException;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.service.AuditoriaService;
import com.hydra.pica.plataforma_pica.reputacion.domain.Calificacion;
import com.hydra.pica.plataforma_pica.reputacion.dto.CalificacionDetalle;
import com.hydra.pica.plataforma_pica.reputacion.dto.CalificacionRequest;
import com.hydra.pica.plataforma_pica.reputacion.dto.JugadorCalificable;
import com.hydra.pica.plataforma_pica.reputacion.dto.Reputacion;
import com.hydra.pica.plataforma_pica.reputacion.repository.CalificacionRepository;
import com.hydra.pica.plataforma_pica.reputacion.repository.SancionRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioPublico;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Calificaciones de jugadores (HU-805, T805-1). El organizador califica a los jugadores de su torneo terminado
 * (FINALIZADO o CANCELADO), una vez por jugador y torneo. Participó quien estuvo en una inscripción confirmada: la
 * suya o la nómina de un equipo, como en las sanciones. El puntaje de reputación es el promedio de las que recibió; la
 * marca de no confiable la ven todos los organizadores al revisar inscripciones y no bloquea al jugador.
 */
@Service
@RequiredArgsConstructor
public class CalificacionService {

    private final CalificacionRepository calificacionRepository;
    private final SancionRepository sancionRepository;
    private final TorneoRepository torneoRepository;
    private final UsuarioRepository usuarioRepository;
    private final AuditoriaService auditoriaService;

    /**
     * 404 TORNEO_NO_ENCONTRADO si no existe o es un borrador; 403 SIN_PERMISO si no lo organiza; 409
     * TORNEO_NO_TERMINADO si no está finalizado ni cancelado; 409 JUGADOR_NO_PARTICIPA si el jugador no estuvo en una
     * inscripción confirmada; 409 JUGADOR_YA_CALIFICADO si ya lo calificó en este torneo.
     */
    @Transactional
    public CalificacionDetalle calificar(Long organizadorId, Long torneoId, CalificacionRequest request) {
        Torneo torneo = propioTerminado(organizadorId, torneoId);
        if (sancionRepository.inscripcionDelJugador(torneoId, request.usuarioId()).isEmpty()) {
            throw new ConflictoException(CodigoError.JUGADOR_NO_PARTICIPA,
                    "El usuario " + request.usuarioId() + " no jugó el torneo " + torneoId)
                    .con("usuarioId", request.usuarioId());
        }
        if (calificacionRepository.existsByTorneoIdAndUsuarioId(torneoId, request.usuarioId())) {
            throw new ConflictoException(CodigoError.JUGADOR_YA_CALIFICADO,
                    "El usuario " + request.usuarioId() + " ya está calificado en el torneo " + torneoId)
                    .con("usuarioId", request.usuarioId());
        }

        Calificacion calificacion = new Calificacion();
        calificacion.setTorneo(torneo);
        calificacion.setOrganizador(usuarioRepository.getReferenceById(organizadorId));
        calificacion.setUsuario(usuarioRepository.getReferenceById(request.usuarioId()));
        calificacion.setPuntaje(request.puntaje().shortValue());
        calificacion.setComentario(request.comentario() == null || request.comentario().isBlank()
                ? null : request.comentario().strip());
        calificacion.setNoConfiable(request.noConfiable());
        calificacion.setCreadaEn(Instant.now());
        calificacion = calificacionRepository.saveAndFlush(calificacion);

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("calificacionId", calificacion.getId());
        detalle.put("usuarioId", request.usuarioId());
        detalle.put("puntaje", request.puntaje());
        detalle.put("comentario", calificacion.getComentario());
        detalle.put("noConfiable", request.noConfiable());
        auditoriaService.registrar(organizadorId, AccionAuditoria.CALIFICACION, "TORNEO", torneoId.toString(), detalle);
        return CalificacionDetalle.desde(calificacionRepository.conTodo(calificacion.getId()).orElseThrow());
    }

    /** Los jugadores del torneo terminado, con su calificación si ya la tienen. Mismos errores que calificar. */
    @Transactional(readOnly = true)
    public List<JugadorCalificable> calificables(Long organizadorId, Long torneoId) {
        propioTerminado(organizadorId, torneoId);
        List<SancionRepository.FilaSancionable> filas = sancionRepository.sancionables(torneoId, null);
        if (filas.isEmpty()) {
            return List.of();
        }
        Map<Long, UsuarioPublico> usuarios = usuarioRepository
                .findConPersonaByIdIn(filas.stream().map(SancionRepository.FilaSancionable::getUsuarioId).toList())
                .stream()
                .collect(Collectors.toMap(Usuario::getId, UsuarioPublico::desde));
        Map<Long, CalificacionDetalle> calificaciones = calificacionRepository.delTorneo(torneoId).stream()
                .map(CalificacionDetalle::desde)
                .collect(Collectors.toMap(c -> c.jugador().id(), Function.identity()));
        return filas.stream()
                .map(f -> new JugadorCalificable(f.getInscripcionId(), f.getEquipo(), usuarios.get(f.getUsuarioId()),
                        calificaciones.get(f.getUsuarioId())))
                .toList();
    }

    /** El puntaje de reputación de cada usuario; sin calificaciones, {@link Reputacion#SIN_CALIFICACIONES}. */
    @Transactional(readOnly = true)
    public Map<Long, Reputacion> reputaciones(Collection<Long> usuarioIds) {
        Map<Long, Reputacion> reputaciones = new HashMap<>();
        usuarioIds.forEach(id -> reputaciones.put(id, Reputacion.SIN_CALIFICACIONES));
        if (!usuarioIds.isEmpty()) {
            calificacionRepository.reputaciones(usuarioIds).forEach(f ->
                    reputaciones.put(f.getUsuarioId(), new Reputacion(f.getPromedio(), f.getCantidad())));
        }
        return reputaciones;
    }

    /** De esos usuarios, los que algún organizador marcó como no confiables. */
    @Transactional(readOnly = true)
    public Set<Long> noConfiables(Collection<Long> usuarioIds) {
        return usuarioIds.isEmpty()
                ? Set.of()
                : new HashSet<>(calificacionRepository.noConfiables(usuarioIds));
    }

    private Torneo propioTerminado(Long organizadorId, Long torneoId) {
        Torneo torneo = torneoRepository.findByIdAndEliminadoEnIsNull(torneoId)
                .filter(t -> t.getEstado() != EstadoTorneo.BORRADOR)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.TORNEO_NO_ENCONTRADO,
                        "No existe el torneo " + torneoId));
        if (!torneo.getOrganizador().getId().equals(organizadorId)) {
            throw new ProhibidoException(CodigoError.SIN_PERMISO,
                    "El usuario " + organizadorId + " no organiza el torneo " + torneoId);
        }
        if (torneo.getEstado() != EstadoTorneo.FINALIZADO && torneo.getEstado() != EstadoTorneo.CANCELADO) {
            throw new ConflictoException(CodigoError.TORNEO_NO_TERMINADO,
                    "El torneo " + torneoId + " está " + torneo.getEstado() + ": se califica cuando termina");
        }
        return torneo;
    }
}
