package com.hydra.pica.plataforma_pica.partido.service;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.partido.repository.PartidoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Confirma los resultados que nadie objetó a tiempo (HU-301, PICA-242). Corre cada
 * {@code app.partidos.revision-plazos}; se apaga con {@code app.partidos.confirmacion-automatica=false} (los tests lo
 * llaman a mano). Cada partido va en su propia transacción: si uno falla, los demás se confirman igual.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfirmacionAutomatica {

    private final ResultadoService resultadoService;
    private final PartidoRepository partidoRepository;

    /** @return cuántos confirmó */
    @Scheduled(fixedDelayString = "${app.partidos.revision-plazos:30s}")
    public int confirmarVencidos() {
        Instant ahora = Instant.now();
        int confirmados = 0;
        for (Long id : partidoRepository.conPlazoVencido(ahora)) {
            try {
                if (resultadoService.confirmarSiVencio(id, ahora)) {
                    confirmados++;
                }
            } catch (RuntimeException e) {
                log.error("No se pudo confirmar el resultado del partido {}", id, e);
            }
        }
        return confirmados;
    }
}
