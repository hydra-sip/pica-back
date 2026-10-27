package com.hydra.pica.plataforma_pica.torneo.service;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cierra la inscripción de los torneos cuya fecha de cierre ya pasó, aunque queden lugares (HU-206, PICA-213). Corre
 * cada {@code app.torneos.revision-cierres}; se apaga con {@code app.torneos.cierre-automatico=false} (los tests la
 * llaman a mano). Cada torneo va en su propia transacción, con la fila bloqueada: si uno falla, los demás se cierran
 * igual, y si corre en varias réplicas a la vez, cada torneo se cierra una sola vez. Mientras no pasa, crear, confirmar
 * y pagar ya rechazan por la fecha.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CierreAutomatico {

    private final InscripcionTorneoService inscripcionTorneoService;
    private final TorneoRepository torneoRepository;

    @Value("${app.torneos.cierre-automatico:true}")
    private boolean activo = true;

    @Scheduled(fixedDelayString = "${app.torneos.revision-cierres:60s}")
    void programado() {
        if (activo) {
            cerrarVencidas();
        }
    }

    /** @return cuántas cerró */
    public int cerrarVencidas() {
        Instant ahora = Instant.now();
        int cerradas = 0;
        for (Long id : torneoRepository.conInscripcionVencida(ahora)) {
            try {
                if (inscripcionTorneoService.cerrarSiVencio(id, ahora)) {
                    cerradas++;
                }
            } catch (RuntimeException e) {
                log.error("No se pudo cerrar la inscripción del torneo {}", id, e);
            }
        }
        return cerradas;
    }
}
