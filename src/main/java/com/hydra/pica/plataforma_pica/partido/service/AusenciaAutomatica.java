package com.hydra.pica.plataforma_pica.partido.service;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.common.config.PartidoConfig.PartidoProperties;
import com.hydra.pica.plataforma_pica.partido.repository.PartidoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Declara las ausencias de los partidos cuya ventana de check-in venció (HU-309, PICA-252). Corre cada
 * {@code app.partidos.revision-plazos}; se apaga con {@code app.partidos.ausencia-automatica=false} (los tests la
 * llaman a mano). Cada partido va en su propia transacción, con la fila bloqueada: si uno falla, los demás se
 * resuelven igual, y si corre en varias réplicas a la vez, cada partido se resuelve una sola vez.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AusenciaAutomatica {

    private final AusenciaService ausenciaService;
    private final PartidoRepository partidoRepository;
    private final PartidoProperties partidoProperties;

    @Value("${app.partidos.ausencia-automatica:true}")
    private boolean activa = true;

    @Scheduled(fixedDelayString = "${app.partidos.revision-plazos:30s}")
    void programada() {
        if (activa) {
            declararVencidas();
        }
    }

    /** @return cuántos partidos resolvió */
    public int declararVencidas() {
        Instant ahora = Instant.now();
        int resueltos = 0;
        for (Long id : partidoRepository.conVentanaVencida(ahora.minus(partidoProperties.toleranciaCheckin()))) {
            try {
                if (ausenciaService.declararSiVencio(id, ahora)) {
                    resueltos++;
                }
            } catch (RuntimeException e) {
                log.error("No se pudo declarar la ausencia del partido {}", id, e);
            }
        }
        return resueltos;
    }
}
