package com.hydra.pica.plataforma_pica.reputacion.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.error.ProhibidoException;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.service.AuditoriaService;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.repository.PartidoRepository;
import com.hydra.pica.plataforma_pica.reputacion.domain.Sancion;
import com.hydra.pica.plataforma_pica.reputacion.dto.SancionDetalle;
import com.hydra.pica.plataforma_pica.reputacion.dto.SancionRequest;
import com.hydra.pica.plataforma_pica.reputacion.event.SancionAplicada;
import com.hydra.pica.plataforma_pica.reputacion.repository.SancionRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.torneo.service.Arbitraje;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sanciones a jugadores (HU-803, T803-1). Las aplica el organizador del torneo o un árbitro que aceptó la designación,
 * a un jugador de una inscripción confirmada (la individual o la nómina de un equipo), con motivo y gravedad. Quedan
 * en la auditoría y publican {@link SancionAplicada}, que avisa al jugador y mueve su índice de fair play.
 */
@Service
@RequiredArgsConstructor
public class SancionService {

    private final SancionRepository sancionRepository;
    private final TorneoRepository torneoRepository;
    private final PartidoRepository partidoRepository;
    private final UsuarioRepository usuarioRepository;
    private final Arbitraje arbitraje;
    private final AuditoriaService auditoriaService;
    private final ApplicationEventPublisher eventos;

    /**
     * 404 TORNEO_NO_ENCONTRADO si no existe o es un borrador; 403 SIN_PERMISO si no lo organiza ni lo arbitra;
     * 409 JUGADOR_NO_PARTICIPA si el jugador no está en una inscripción confirmada; 400 VALIDACION si el partido no
     * es del torneo o el jugador no lo juega.
     */
    @Transactional
    public SancionDetalle aplicar(Long usuarioId, Long torneoId, SancionRequest request) {
        if (request.motivo() == null || request.motivo().isBlank()) {
            // el controller ya lo valida; esto cubre a quien llame al servicio directo
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay campos inválidos")
                    .con("errores", List.of(new ErrorCampo("motivo", ErrorCampo.Codigo.REQUERIDO, "Falta el motivo")));
        }
        Torneo torneo = torneo(torneoId);
        if (!puedeSancionar(torneo, usuarioId)) {
            throw new ProhibidoException(CodigoError.SIN_PERMISO,
                    "El usuario " + usuarioId + " no organiza ni arbitra el torneo " + torneoId);
        }
        Long inscripcionId = sancionRepository.inscripcionDelJugador(torneoId, request.usuarioId())
                .orElseThrow(() -> new ConflictoException(CodigoError.JUGADOR_NO_PARTICIPA,
                        "El usuario " + request.usuarioId() + " no juega el torneo " + torneoId)
                        .con("usuarioId", request.usuarioId()));
        Partido partido = null;
        if (request.partidoId() != null) {
            partido = partidoRepository.findById(request.partidoId())
                    .filter(p -> p.getTorneo().getId().equals(torneoId))
                    .filter(p -> sancionRepository.juega(p.getId(), inscripcionId))
                    .orElse(null);
            if (partido == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay campos inválidos")
                        .con("errores", List.of(new ErrorCampo("partidoId", ErrorCampo.Codigo.VALOR_INVALIDO,
                                "El partido tiene que ser del torneo y del jugador")));
            }
        }

        Sancion sancion = new Sancion();
        sancion.setTorneo(torneo);
        sancion.setUsuario(usuarioRepository.getReferenceById(request.usuarioId()));
        sancion.setPartido(partido);
        sancion.setGravedad(request.gravedad());
        sancion.setMotivo(request.motivo().strip());
        sancion.setAplicadaPor(usuarioRepository.getReferenceById(usuarioId));
        sancion.setAplicadaEn(Instant.now());
        sancion = sancionRepository.saveAndFlush(sancion);

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("sancionId", sancion.getId());
        detalle.put("usuarioId", request.usuarioId());
        detalle.put("partidoId", request.partidoId());
        detalle.put("gravedad", request.gravedad().name());
        detalle.put("motivo", sancion.getMotivo());
        auditoriaService.registrar(usuarioId, AccionAuditoria.SANCION, "TORNEO", torneoId.toString(), detalle);
        eventos.publishEvent(new SancionAplicada(sancion.getId(), torneoId, request.usuarioId(), request.gravedad()));
        return SancionDetalle.desde(sancionRepository.conTodo(sancion.getId()).orElseThrow());
    }

    /** Las del torneo, la última primero. Las ve cualquiera con sesión, como la ficha del torneo. */
    @Transactional(readOnly = true)
    public List<SancionDetalle> delTorneo(Long torneoId) {
        torneo(torneoId);
        return sancionRepository.delTorneo(torneoId).stream().map(SancionDetalle::desde).toList();
    }

    /** Las que recibió el usuario, en todos los torneos. */
    @Transactional(readOnly = true)
    public List<SancionDetalle> mias(Long usuarioId) {
        return sancionRepository.delJugador(usuarioId).stream().map(SancionDetalle::desde).toList();
    }

    /** Para que el front sepa si muestra el botón de sancionar. */
    @Transactional(readOnly = true)
    public boolean puedeSancionar(Torneo torneo, Long usuarioId) {
        return usuarioId != null && (torneo.getOrganizador().getId().equals(usuarioId)
                || arbitraje.esArbitroAceptado(torneo.getId(), usuarioId));
    }

    private Torneo torneo(Long torneoId) {
        return torneoRepository.findByIdAndEliminadoEnIsNull(torneoId)
                .filter(t -> t.getEstado() != EstadoTorneo.BORRADOR)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.TORNEO_NO_ENCONTRADO,
                        "No existe el torneo " + torneoId));
    }
}
