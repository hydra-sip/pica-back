package com.hydra.pica.plataforma_pica.reputacion.service;

import com.hydra.pica.plataforma_pica.billetera.event.TorneoLiquidado;
import com.hydra.pica.plataforma_pica.partido.event.CheckinHecho;
import com.hydra.pica.plataforma_pica.partido.event.ResultadoConfirmado;
import com.hydra.pica.plataforma_pica.rifa.event.RifaSorteada;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Otorga insignias automáticamente después del commit de cada evento relevante (HU-804, T804-1). Cada evaluación
 * corre en su propia transacción: si falla, la operación principal ya confirmada queda intacta y se registra en el log.
 */
@Component
public class InsigniaAutomatica {

    private static final Logger log = LoggerFactory.getLogger(InsigniaAutomatica.class);

    private final InsigniaService insigniaService;
    private final TransactionTemplate nueva;

    public InsigniaAutomatica(InsigniaService insigniaService, PlatformTransactionManager transacciones) {
        this.insigniaService = insigniaService;
        this.nueva = new TransactionTemplate(transacciones);
        this.nueva.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void alConfirmarResultado(ResultadoConfirmado evento) {
        evaluar(() -> insigniaService.evaluarResultadoConfirmado(
                        evento.torneoId(), evento.partidoId(), evento.torneoFinalizado()),
                "el resultado del partido " + evento.partidoId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void alLiquidarTorneo(TorneoLiquidado evento) {
        evaluar(() -> insigniaService.evaluarTorneoFinalizado(evento.torneoId()),
                "la liquidación del torneo " + evento.torneoId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void alSortearRifa(RifaSorteada evento) {
        evaluar(() -> insigniaService.evaluarRifaSorteada(evento.rifaId()),
                "el sorteo de la rifa " + evento.rifaId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void alHacerCheckin(CheckinHecho evento) {
        evaluar(() -> insigniaService.evaluarCheckin(evento.inscripcionId()),
                "el check-in de la inscripción " + evento.inscripcionId());
    }

    private void evaluar(Runnable accion, String que) {
        try {
            nueva.executeWithoutResult(estado -> accion.run());
        } catch (RuntimeException e) {
            log.error("No se pudieron evaluar las insignias tras {}", que, e);
        }
    }
}
