package com.hydra.pica.plataforma_pica.torneo.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.hydra.pica.plataforma_pica.billetera.service.Reintegrador;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.error.ProhibidoException;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.domain.EntidadAuditoria;
import com.hydra.pica.plataforma_pica.control.service.AuditoriaService;
import com.hydra.pica.plataforma_pica.equipo.repository.NominaRepository;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.MinimoInscriptos;
import com.hydra.pica.plataforma_pica.torneo.domain.Reprogramacion;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.dto.RechazoReprogramacion;
import com.hydra.pica.plataforma_pica.torneo.event.ReprogramacionRechazada;
import com.hydra.pica.plataforma_pica.torneo.repository.ReprogramacionRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El inscripto que no puede en la nueva fecha se baja dentro del plazo de la reprogramación (CR-01-3, PICA-392): la
 * inscripción pasa a REINTEGRADA, libera su lugar y se le devuelve la inscripción a la billetera de quien pagó
 * ({@link Reintegrador#reintegrarInscripcion}); los gastos administrativos no se devuelven. Lo hace el participante de
 * la individual o el capitán del equipo. Si con las confirmadas que quedan el torneo no llega al mínimo para jugar, no
 * se cancela: se avisa al organizador para que decida ({@link ReprogramacionRechazada}).
 */
@Service
@RequiredArgsConstructor
public class RechazoReprogramacionService {

    private final InscripcionRepository inscripcionRepository;
    private final NominaRepository nominaRepository;
    private final TorneoRepository torneoRepository;
    private final ReprogramacionRepository reprogramacionRepository;
    private final Reintegrador reintegrador;
    private final AuditoriaService auditoriaService;
    private final ApplicationEventPublisher eventos;

    /**
     * Bloquea el torneo antes de leer la inscripción: dos pedidos a la vez se ordenan y el segundo la encuentra ya
     * REINTEGRADA (409), así no se devuelve dos veces.
     */
    @Transactional
    public RechazoReprogramacion rechazar(Long usuarioId, Long inscripcionId) {
        Long torneoId = inscripcionRepository.torneoIdDeInscripcionPropia(inscripcionId, usuarioId)
                .orElseThrow(() -> nominaRepository.existsByIdInscripcionIdAndIdUsuarioId(inscripcionId, usuarioId)
                        ? new ProhibidoException(CodigoError.NO_ES_CAPITAN,
                                "Solo el capitán puede rechazar la nueva fecha del torneo")
                        : new NoEncontradoException(CodigoError.INSCRIPCION_NO_ENCONTRADA,
                                "No existe la inscripción " + inscripcionId));
        Torneo torneo = torneoRepository.buscarBloqueado(torneoId).orElseThrow();
        Inscripcion inscripcion = inscripcionRepository.findById(inscripcionId).orElseThrow();
        if (inscripcion.getEstado() != EstadoInscripcion.CONFIRMADA) {
            throw new ConflictoException(CodigoError.INSCRIPCION_NO_EDITABLE, "La inscripción " + inscripcionId
                    + " está " + inscripcion.getEstado() + ": solo se rechaza la nueva fecha con una confirmada");
        }
        Instant ahora = Instant.now();
        Reprogramacion reprogramacion = reprogramacionRepository.findByTorneoIdAndPlazoProcesadoEnIsNull(torneoId)
                .filter(r -> !ahora.isAfter(r.getRechazoHasta()))
                .orElseThrow(() -> new ConflictoException(CodigoError.PLAZO_RECHAZO_VENCIDO,
                        "El torneo " + torneoId + " no tiene una reprogramación con el plazo para rechazar abierto"));

        inscripcion.setEstado(EstadoInscripcion.REINTEGRADA);
        inscripcionRepository.saveAndFlush(inscripcion);
        BigDecimal reintegrado = reintegrador.reintegrarInscripcion(torneoId, inscripcionId,
                "reint-t" + torneoId + "-reprog-" + inscripcionId,
                "Reintegro de la inscripción al torneo " + torneo.getNombre() + ": la nueva fecha no te sirve");

        long confirmadas = inscripcionRepository.countByTorneoIdAndEstado(torneoId, EstadoInscripcion.CONFIRMADA);
        boolean bajoElMinimo = confirmadas < MinimoInscriptos.de(torneo);
        auditar(usuarioId, reprogramacion, inscripcionId, reintegrado, confirmadas, bajoElMinimo);
        eventos.publishEvent(new ReprogramacionRechazada(torneoId, reprogramacion.getId(), inscripcionId,
                bajoElMinimo));
        return new RechazoReprogramacion(inscripcionId, torneoId, inscripcion.getEstado().name(), reintegrado);
    }

    private void auditar(Long usuarioId, Reprogramacion reprogramacion, Long inscripcionId, BigDecimal reintegrado,
                         long confirmadas, boolean bajoElMinimo) {
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("rechazo", true);
        detalle.put("reprogramacionId", reprogramacion.getId());
        detalle.put("torneoId", reprogramacion.getTorneo().getId());
        detalle.put("reintegrado", reintegrado);
        detalle.put("confirmadas", confirmadas);
        detalle.put("bajoElMinimo", bajoElMinimo);
        auditoriaService.registrar(usuarioId, AccionAuditoria.REPROGRAMACION, EntidadAuditoria.INSCRIPCION,
                inscripcionId.toString(), detalle);
    }
}
