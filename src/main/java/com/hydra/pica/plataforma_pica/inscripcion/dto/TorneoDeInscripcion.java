package com.hydra.pica.plataforma_pica.inscripcion.dto;

import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;

/**
 * El torneo de una inscripción, lo justo para que el front lo muestre y llegue a su ficha pública: el slug es
 * nulo mientras el torneo no se publicó. El estado dice en qué momento está (Mis inscripciones, HU-209).
 */
public record TorneoDeInscripcion(Long id, String nombre, String slug, EstadoTorneo estado) {

    public static TorneoDeInscripcion de(Torneo torneo) {
        return new TorneoDeInscripcion(torneo.getId(), torneo.getNombre(), torneo.getSlug(), torneo.getEstado());
    }
}
