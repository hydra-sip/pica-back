package com.hydra.pica.plataforma_pica.partido.event;

import java.util.List;

/**
 * Venció la ventana de check-in y estos lados no lo hicieron (HU-309): el ausente de un walkover o los dos de un
 * partido sin presentes, por id. Resta del índice de fair play de quien responde por cada uno (HU-802).
 */
public record AusenciaDeclarada(Long torneoId, Long partidoId, List<Long> inscripcionIds) {

    public AusenciaDeclarada {
        inscripcionIds = inscripcionIds.stream().sorted().toList();
    }
}
