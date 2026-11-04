package com.hydra.pica.plataforma_pica.rifa.service;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cierra la venta de las rifas cuyo {@code ventaHasta} ya pasó y sella su lista (HU-407, PICA-261). Corre cada
 * {@code app.rifas.revision-cierres}; se apaga con {@code app.rifas.cierre-automatico=false} (los tests la llaman a
 * mano). Cada rifa va en su propia transacción, con la fila bloqueada: si una falla, las demás se cierran igual, y si
 * corre en varias réplicas a la vez, cada rifa se cierra una sola vez. Mientras no pasa, reservar y comprar ya
 * rechazan por la fecha (409 VENTA_CERRADA).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CierreAutomaticoRifa {

    private final CierreVentaService cierreVentaService;
    private final RifaRepository rifaRepository;

    @Value("${app.rifas.cierre-automatico:true}")
    private boolean activo = true;

    @Scheduled(fixedDelayString = "${app.rifas.revision-cierres:60s}")
    void programado() {
        if (activo) {
            cerrarVencidas();
        }
    }

    /** @return cuántas cerró */
    public int cerrarVencidas() {
        Instant ahora = Instant.now();
        int cerradas = 0;
        for (Long id : rifaRepository.conVentaVencida(ahora)) {
            try {
                if (cierreVentaService.cerrarSiVencio(id, ahora)) {
                    cerradas++;
                }
            } catch (RuntimeException e) {
                log.error("No se pudo cerrar la venta de la rifa {}", id, e);
            }
        }
        return cerradas;
    }
}
