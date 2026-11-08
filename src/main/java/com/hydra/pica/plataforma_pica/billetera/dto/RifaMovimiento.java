package com.hydra.pica.plataforma_pica.billetera.dto;

import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;

/**
 * Resumen de la rifa asociada a un movimiento de billetera.
 */
public record RifaMovimiento(Long id, String titulo) {

    public static RifaMovimiento de(Rifa rifa) {
        if (rifa == null) {
            return null;
        }
        return new RifaMovimiento(rifa.getId(), rifa.getTitulo());
    }
}

