package com.hydra.pica.plataforma_pica.rifa.dto;

import com.hydra.pica.plataforma_pica.rifa.domain.PremioRifa;

/** {@code numeroGanador} es null hasta el sorteo. */
public record PremioRifaDetalle(int puesto, int pct, String descripcion, Integer numeroGanador) {

    public static PremioRifaDetalle de(PremioRifa premio) {
        return new PremioRifaDetalle(premio.getId().getPuesto(), premio.getPct(), premio.getDescripcion(),
                premio.getNumeroGanador());
    }
}
