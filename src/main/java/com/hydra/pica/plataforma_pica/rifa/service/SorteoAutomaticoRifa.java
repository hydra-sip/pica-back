package com.hydra.pica.plataforma_pica.rifa.service;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sortea las rifas CERRADAS cuya {@code fechaSorteo} ya llegó (HU-504, PICA-265), como {@link CierreAutomaticoRifa}:
 * corre cada {@code app.rifas.revision-cierres} y se apaga con {@code app.rifas.sorteo-automatico=false} (los tests
 * la llaman a mano). Cada rifa va en su propia transacción, con la fila bloqueada.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SorteoAutomaticoRifa {

    private final SorteoRifaService sorteoRifaService;
    private final RifaRepository rifaRepository;

    @Value("${app.rifas.sorteo-automatico:true}")
    private boolean activo = true;

    @Scheduled(fixedDelayString = "${app.rifas.revision-cierres:60s}")
    void programado() {
        if (activo) {
            sortearLasQueLlegaron();
        }
    }

    /** @return cuántas sorteó */
    public int sortearLasQueLlegaron() {
        Instant ahora = Instant.now();
        int sorteadas = 0;
        for (Long id : rifaRepository.paraSortear(ahora)) {
            try {
                if (sorteoRifaService.sortearSiLlegoLaFecha(id, ahora)) {
                    sorteadas++;
                }
            } catch (RuntimeException e) {
                log.error("No se pudo sortear la rifa {}", id, e);
            }
        }
        return sorteadas;
    }
}
