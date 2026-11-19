package com.hydra.pica.plataforma_pica.reputacion.service;

import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.event.CancelacionResuelta;
import com.hydra.pica.plataforma_pica.torneo.event.TorneoEstadoCambiado;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Actualiza el índice de confiabilidad del organizador después del commit cuando un torneo se cancela (HU-806).
 * Cada movimiento corre en su propia transacción: si falla, la cancelación confirmada queda intacta y se registra en el
 * log.
 */
@Component
public class IndiceOrganizadorAutomatico {

    private static final Logger log = LoggerFactory.getLogger(IndiceOrganizadorAutomatico.class);

    private final PerfilOrganizadorService perfilOrganizadorService;
    private final TransactionTemplate nueva;

    public IndiceOrganizadorAutomatico(
            PerfilOrganizadorService perfilOrganizadorService, PlatformTransactionManager transacciones) {
        this.perfilOrganizadorService = perfilOrganizadorService;
        this.nueva = new TransactionTemplate(transacciones);
        this.nueva.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void alCambiarEstadoTorneo(TorneoEstadoCambiado evento) {
        if (evento.estadoNuevo() == EstadoTorneo.CANCELADO) {
            registrar(evento.torneoId());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void alResolverCancelacion(CancelacionResuelta evento) {
        registrar(evento.torneoId());
    }

    private void registrar(Long torneoId) {
        try {
            nueva.executeWithoutResult(estado -> perfilOrganizadorService.registrarCancelacion(torneoId));
        } catch (RuntimeException e) {
            log.error("No se pudo actualizar el índice del organizador por la cancelación del torneo {}", torneoId, e);
        }
    }
}
