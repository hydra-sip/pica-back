package com.hydra.pica.plataforma_pica.billetera.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.hydra.pica.plataforma_pica.billetera.repository.CustodiaRepository;
import com.hydra.pica.plataforma_pica.partido.event.ResultadoConfirmado;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.rifa.service.ReclamoRifaService;
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
 * Liquida solo cada evento que termina (HU-606). Un torneo, al confirmarse la final (después del commit) y en la
 * revisión, por si quedó sin liquidar (la primera vez falló o la app se reinició en el medio). Una rifa, en la revisión,
 * cuando vence el plazo de reclamo sin reclamos pendientes. La revisión corre cada {@code app.liquidacion.revision}:
 * así se liquida en menos de 5 minutos. Cada evento va en su propia transacción: si uno falla, los demás se liquidan
 * igual. Se apaga con {@code app.liquidacion.automatica=false} (los tests llaman a mano).
 */
@Slf4j
@Component
public class LiquidacionAutomatica {

    private final LiquidacionService liquidacionService;
    private final LiquidacionRifaService liquidacionRifaService;
    private final CustodiaRepository custodiaRepository;
    private final RifaRepository rifaRepository;
    private final TransactionTemplate propia;
    private final boolean activa;

    public LiquidacionAutomatica(LiquidacionService liquidacionService, LiquidacionRifaService liquidacionRifaService,
                                 CustodiaRepository custodiaRepository, RifaRepository rifaRepository,
                                 PlatformTransactionManager transacciones,
                                 @Value("${app.liquidacion.automatica:true}") boolean activa) {
        this.liquidacionService = liquidacionService;
        this.liquidacionRifaService = liquidacionRifaService;
        this.custodiaRepository = custodiaRepository;
        this.rifaRepository = rifaRepository;
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

    /** @return cuántos eventos liquidó: primero los torneos, así una rifa encuentra el suyo liquidado */
    public int liquidarPendientes() {
        int liquidados = 0;
        for (Long torneoId : custodiaRepository.torneosFinalizadosSinLiquidar()) {
            if (liquidar(torneoId)) {
                liquidados++;
            }
        }
        Instant ahora = Instant.now();
        for (Long rifaId : rifaRepository.paraLiquidar(
                ahora.minus(ReclamoRifaService.HORAS_LIMITE_RECLAMO, ChronoUnit.HOURS))) {
            if (liquidarRifa(rifaId, ahora)) {
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

    private boolean liquidarRifa(Long rifaId, Instant ahora) {
        try {
            propia.executeWithoutResult(estado -> liquidacionRifaService.liquidarRifa(rifaId, ahora));
            log.info("Rifa {} liquidada", rifaId);
            return true;
        } catch (RuntimeException e) {
            log.error("No se pudo liquidar la rifa {}", rifaId, e);
            return false;
        }
    }
}
