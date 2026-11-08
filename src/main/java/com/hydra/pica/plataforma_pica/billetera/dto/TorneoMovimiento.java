package com.hydra.pica.plataforma_pica.billetera.dto;

import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;

/**
 * Resumen del torneo asociado a un movimiento de billetera.
 */
public record TorneoMovimiento(Long id, String titulo, String nombre, String slug) {

    public static TorneoMovimiento de(Torneo torneo) {
        if (torneo == null) {
            return null;
        }
        return new TorneoMovimiento(torneo.getId(), torneo.getNombre(), torneo.getNombre(), torneo.getSlug());
    }
}

