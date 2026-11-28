package com.hydra.pica.plataforma_pica.billetera.service;

import java.time.Clock;
import java.time.Instant;

import com.hydra.pica.plataforma_pica.billetera.domain.EstadoRetiroCripto;
import com.hydra.pica.plataforma_pica.billetera.repository.RetiroCriptoRepository;
import com.hydra.pica.plataforma_pica.common.config.CriptoConfig.RetirosCriptoProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Resuelve los retiros a la wallet que vencieron sin que el front avisara el cobro (HU-610, PICA-455): corre cada
 * {@code app.cripto.retiros.revision} y se apaga con {@code app.cripto.retiros.devolucion-automatica=false} (los
 * tests la llaman a mano). Espera {@code margen-devolucion} después del vencimiento y le pregunta al contrato: si se
 * cobró, el retiro queda cobrado; si no, el saldo vuelve a la billetera. Si la red no contesta, lo reintenta en la
 * vuelta siguiente.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DevolucionRetirosCripto {

    private final RetiroCriptoService retiroCriptoService;
    private final RetiroCriptoRepository retiroCriptoRepository;
    private final RetirosCriptoProperties retiros;
    private final Clock clock;

    @Value("${app.cripto.retiros.devolucion-automatica:true}")
    private boolean activa = true;

    @Scheduled(fixedDelayString = "${app.cripto.retiros.revision:60s}")
    void programada() {
        if (activa && retiros.habilitados()) {
            resolverVencidos();
        }
    }

    /** @return cuántos devolvió */
    public int resolverVencidos() {
        int devueltos = 0;
        for (Long id : retiroCriptoRepository.pendientesVencidosAntesDe(Instant.now(clock).minus(retiros.margenDevolucion()))) {
            try {
                if (retiroCriptoService.devolverSiVencio(id) == EstadoRetiroCripto.DEVUELTO) {
                    devueltos++;
                }
            } catch (RuntimeException e) {
                log.warn("No se pudo resolver el retiro a la wallet {}: {}", id, e.getMessage());
            }
        }
        return devueltos;
    }
}
