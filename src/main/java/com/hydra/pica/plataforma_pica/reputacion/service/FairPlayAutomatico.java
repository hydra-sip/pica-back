package com.hydra.pica.plataforma_pica.reputacion.service;

import com.hydra.pica.plataforma_pica.partido.event.AusenciaDeclarada;
import com.hydra.pica.plataforma_pica.partido.event.CheckinHecho;
import com.hydra.pica.plataforma_pica.reputacion.domain.TipoEventoFairPlay;
import com.hydra.pica.plataforma_pica.reputacion.event.SancionAplicada;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Recalcula el índice de fair play con cada evento, después del commit (HU-802): un check-in o una ausencia mueve el
 * de quien responde por la inscripción (el jugador de la individual, el capitán del equipo); una sanción, el del
 * sancionado. Cada movimiento va en su propia transacción: si falla, lo que pasó queda igual y se deja en el log.
 */
@Component
public class FairPlayAutomatico {

    private static final Logger log = LoggerFactory.getLogger(FairPlayAutomatico.class);

    private final FairPlayService fairPlayService;
    private final TransactionTemplate nueva;

    public FairPlayAutomatico(FairPlayService fairPlayService, PlatformTransactionManager transacciones) {
        this.fairPlayService = fairPlayService;
        this.nueva = new TransactionTemplate(transacciones);
        this.nueva.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void alHacerCheckin(CheckinHecho evento) {
        mover(() -> fairPlayService.registrarDeInscripcion(evento.inscripcionId(), TipoEventoFairPlay.CHECKIN,
                evento.torneoId(), evento.partidoId()), "el check-in del partido " + evento.partidoId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void alDeclararAusencia(AusenciaDeclarada evento) {
        for (Long inscripcionId : evento.inscripcionIds()) {
            mover(() -> fairPlayService.registrarDeInscripcion(inscripcionId, TipoEventoFairPlay.AUSENCIA,
                    evento.torneoId(), evento.partidoId()),
                    "la ausencia de la inscripción " + inscripcionId + " en el partido " + evento.partidoId());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void alSancionar(SancionAplicada evento) {
        mover(() -> fairPlayService.registrarSancion(evento.usuarioId(), evento.torneoId(), evento.sancionId(),
                evento.gravedad()), "la sanción " + evento.sancionId());
    }

    private void mover(Runnable movimiento, String que) {
        try {
            nueva.executeWithoutResult(estado -> movimiento.run());
        } catch (RuntimeException e) {
            log.error("No se pudo mover el fair play por {}", que, e);
        }
    }
}
