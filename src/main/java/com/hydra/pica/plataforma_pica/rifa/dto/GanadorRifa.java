package com.hydra.pica.plataforma_pica.rifa.dto;

import com.hydra.pica.plataforma_pica.rifa.domain.PremioRifa;

/**
 * Un premio de la rifa sorteada en el portal de verificación (HU-504): el número que ganó y el username de quien lo
 * compró. Los dos son null si hubo más premios que números vendidos.
 */
public record GanadorRifa(int puesto, int pct, String descripcion, Integer numeroGanador, String ganador) {

    public static GanadorRifa de(PremioRifa premio, String ganador) {
        return new GanadorRifa(premio.getId().getPuesto(), premio.getPct(), premio.getDescripcion(),
                premio.getNumeroGanador(), ganador);
    }
}
