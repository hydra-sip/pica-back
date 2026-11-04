package com.hydra.pica.plataforma_pica.partido.service;

import java.time.Duration;
import java.time.Instant;

import com.hydra.pica.plataforma_pica.common.config.PartidoConfig.PartidoProperties;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.dto.CheckinPartido.EstadoVentana;
import com.hydra.pica.plataforma_pica.partido.dto.CheckinPartido.VentanaCheckin;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * La ventana de check-in de un partido (HU-308) y su estado con la tolerancia: lo que comparten el check-in de cada
 * capitán ({@link CheckinService}) y la ausencia al vencer la ventana (HU-309). La ventana abre {@code minAntes}
 * minutos antes del partido y dura {@code duracion}, los dos del torneo; se calcula al consultar.
 */
@Component
@RequiredArgsConstructor
public class VentanasCheckin {

    private final PartidoProperties partidoProperties;

    /** Null si el torneo no la configuró o el partido no tiene fecha. */
    public static VentanaCheckin de(Partido partido) {
        Torneo torneo = partido.getTorneo();
        if (partido.getFechaProgramada() == null || torneo.getCheckinMinAntes() == null) {
            return null;
        }
        Instant abreEn = partido.getFechaProgramada().minus(Duration.ofMinutes(torneo.getCheckinMinAntes()));
        return new VentanaCheckin(abreEn, abreEn.plus(Duration.ofMinutes(torneo.getCheckinDuracionMin())));
    }

    /** Con la tolerancia en cada extremo, por diferencias de reloj. */
    public EstadoVentana estado(VentanaCheckin ventana, Instant ahora) {
        if (ventana == null) {
            return EstadoVentana.SIN_VENTANA;
        }
        Duration tolerancia = partidoProperties.toleranciaCheckin();
        if (ahora.isBefore(ventana.abreEn().minus(tolerancia))) {
            return EstadoVentana.ANTES;
        }
        return ahora.isAfter(ventana.cierraEn().plus(tolerancia)) ? EstadoVentana.CERRADA : EstadoVentana.ABIERTA;
    }
}
