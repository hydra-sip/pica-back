package com.hydra.pica.plataforma_pica.control.service;

import com.hydra.pica.plataforma_pica.billetera.event.CuentaBancariaRegistrada;
import com.hydra.pica.plataforma_pica.billetera.event.MovimientoBilleteraRegistrado;
import com.hydra.pica.plataforma_pica.partido.event.ResultadoObjetado;
import com.hydra.pica.plataforma_pica.rifa.event.NumerosVendidos;
import com.hydra.pica.plataforma_pica.torneo.event.CancelacionResuelta;
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
 * Escucha los eventos de dominio después del commit y ejecuta la pasada diaria programada para detectar conductas
 * fraudulentas (HU-705, PICA-416). Cada evaluación corre en una transacción propia ({@code PROPAGATION_REQUIRES_NEW})
 * para no interferir con la operación original si algo falla.
 */
@Slf4j
@Component
public class DeteccionFraudeAutomatica {

    private final DeteccionFraudeService deteccionFraudeService;
    private final TransactionTemplate nueva;
    private final boolean activa;

    public DeteccionFraudeAutomatica(
            DeteccionFraudeService deteccionFraudeService,
            PlatformTransactionManager transacciones,
            @Value("${app.fraude.deteccion-automatica:true}") boolean activa) {
        this.deteccionFraudeService = deteccionFraudeService;
        this.nueva = new TransactionTemplate(transacciones);
        this.nueva.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.activa = activa;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void alVenderNumeros(NumerosVendidos evento) {
        ejecutar(() -> deteccionFraudeService.evaluarCompraRifa(evento.rifaId(), evento.compradorId()),
                "compra de números en rifa " + evento.rifaId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void alRegistrarMovimiento(MovimientoBilleteraRegistrado evento) {
        ejecutar(() -> deteccionFraudeService.evaluarCarruselBilletera(evento.usuarioId()),
                "movimiento de billetera de usuario " + evento.usuarioId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void alResolverCancelacion(CancelacionResuelta evento) {
        ejecutar(() -> deteccionFraudeService.evaluarCancelacionesOrganizador(evento.torneoId()),
                "cancelación del torneo " + evento.torneoId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void alObjetarResultado(ResultadoObjetado evento) {
        ejecutar(() -> deteccionFraudeService.evaluarObjecionesUsuario(evento.torneoId(), evento.partidoId()),
                "objeción del partido " + evento.partidoId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void alRegistrarCuentaBancaria(CuentaBancariaRegistrada evento) {
        ejecutar(() -> deteccionFraudeService.evaluarCuentaCompartida(evento.usuarioId(), evento.valor()),
                "registro de cuenta bancaria de usuario " + evento.usuarioId());
    }

    @Scheduled(cron = "${app.fraude.cron:0 0 3 * * *}")
    public void pasadaDiaria() {
        if (!activa) {
            return;
        }
        ejecutar(() -> {
            int nuevas = deteccionFraudeService.evaluarTodo();
            if (nuevas > 0) {
                log.info("Pasada diaria de detección de fraude: {} alertas nuevas generadas", nuevas);
            }
        }, "pasada diaria de detección de fraude");
    }

    private void ejecutar(Runnable tarea, String contexto) {
        try {
            nueva.executeWithoutResult(status -> tarea.run());
        } catch (RuntimeException e) {
            log.error("No se pudo evaluar fraude para {}", contexto, e);
        }
    }
}
