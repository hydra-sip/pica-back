package com.hydra.pica.plataforma_pica.billetera.service;

import com.hydra.pica.plataforma_pica.billetera.repository.CustodiaRepository;
import com.hydra.pica.plataforma_pica.partido.event.ResultadoConfirmado;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Liquida solo cada torneo que termina (HU-606): al confirmarse la final, después del commit, y cada
 * {@code app.liquidacion.revision} los que quedaron finalizados sin liquidar (si la primera vez falló o la app se
 * reinició en el medio). Así se liquida en menos de 5 minutos. Cada torneo va en su propia transacción: si uno falla,
 * los demás se liquidan igual. Se apaga con {@code app.liquidacion.automatica=false} (los tests llaman a mano).
 */
@Slf4j
@Component
public class LiquidacionAutomatica {

    private final LiquidacionService liquidacionService;
    private final CustodiaRepository custodiaRepository;
    private final TransactionTemplate propia;
    private final boolean activa;

    public LiquidacionAutomatica(LiquidacionService liquidacionService, CustodiaRepository custodiaRepository,
                                 PlatformTransactionManager transacciones,
                                 @Value("${app.liquidacion.automatica:true}") boolean activa) {
        this.liquidacionService = liquidacionService;
        this.custodiaRepository = custodiaRepository;
        this.propia = new TransactionTemplate(transacciones);
        this.propia.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.activa = activa;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void alConfirmarResultado(ResultadoConfirmado evento) {
        if (activa && evento.torneoFinalizado()) {
            liquidar(evento.torneoId());
        }
    }

    @Scheduled(fixedDelayString = "${app.liquidacion.revision:60s}")
    void programado() {
        if (activa) {
            liquidarPendientes();
        }
    }

    /** @return cuántos liquidó */
    public int liquidarPendientes() {
        int liquidados = 0;
        for (Long torneoId : custodiaRepository.torneosFinalizadosSinLiquidar()) {
            if (liquidar(torneoId)) {
                liquidados++;
            }
        }
        return liquidados;
    }

    private boolean liquidar(Long torneoId) {
        try {
            propia.executeWithoutResult(estado -> liquidacionService.liquidarTorneo(torneoId));
            log.info("Torneo {} liquidado", torneoId);
            return true;
        } catch (RuntimeException e) {
            // la revisión lo vuelve a intentar; si ya estaba liquidado, CUSTODIA_LIBERADA y no paga dos veces
            log.error("No se pudo liquidar el torneo {}", torneoId, e);
            return false;
        }
    }
}
