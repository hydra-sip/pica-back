package com.hydra.pica.plataforma_pica.torneo.service;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.torneo.repository.ReprogramacionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Resuelve las reprogramaciones con el plazo para rechazar vencido (CR-01, PICA-391): abre la inscripción si quedaron
 * lugares o la vuelve a cerrar si el cupo sigue completo ({@link ReprogramacionTorneoService#resolverPlazoSiVencio}).
 * Corre junto con el cierre de inscripciones ({@link CierreAutomatico}): cada {@code app.torneos.revision-cierres} y se
 * apaga con {@code app.torneos.cierre-automatico=false} (los tests la llaman a mano). Cada torneo va en su propia
 * transacción: si uno falla, los demás se resuelven igual.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VencimientoReprogramacion {

    private final ReprogramacionTorneoService reprogramacionTorneoService;
    private final ReprogramacionRepository reprogramacionRepository;

    @Value("${app.torneos.cierre-automatico:true}")
    private boolean activo = true;

    @Scheduled(fixedDelayString = "${app.torneos.revision-cierres:60s}")
    void programado() {
        if (activo) {
            recordar();
            resolverVencidas();
        }
    }

    /**
     * El recordatorio del día antes de que venza el plazo (CR-01-4, {@link ReprogramacionTorneoService#recordarSiFaltaPoco}).
     *
     * @return cuántos mandó
     */
    public int recordar() {
        Instant ahora = Instant.now();
        int recordados = 0;
        for (Long torneoId : reprogramacionRepository.torneosParaRecordar(ahora,
                ahora.plus(ReprogramacionTorneoService.RECORDATORIO))) {
            try {
                if (reprogramacionTorneoService.recordarSiFaltaPoco(torneoId, ahora)) {
                    recordados++;
                }
            } catch (RuntimeException e) {
                log.error("No se pudo mandar el recordatorio de la reprogramación del torneo {}", torneoId, e);
            }
        }
        return recordados;
    }

    /** @return cuántas resolvió */
    public int resolverVencidas() {
        Instant ahora = Instant.now();
        int resueltas = 0;
        for (Long torneoId : reprogramacionRepository.torneosConPlazoVencido(ahora)) {
            try {
                if (reprogramacionTorneoService.resolverPlazoSiVencio(torneoId, ahora)) {
                    resueltas++;
                }
            } catch (RuntimeException e) {
                log.error("No se pudo resolver el plazo de la reprogramación del torneo {}", torneoId, e);
            }
        }
        return resueltas;
    }
}
