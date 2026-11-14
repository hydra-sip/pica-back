package com.hydra.pica.plataforma_pica.rifa.event;

import com.hydra.pica.plataforma_pica.rifa.domain.EstadoReclamo;
import com.hydra.pica.plataforma_pica.rifa.dto.DecisionReclamo;

/**
 * Evento emitido cuando soporte/administración resuelve definitivamente un reclamo de rifa (HU-706, PICA-339).
 */
public record ReclamoRifaResuelto(
        Long reclamoId,
        Long rifaId,
        Long torneoId,
        DecisionReclamo decision,
        EstadoReclamo estadoResultante,
        String resolucion,
        boolean rifaSalioDeRevision
) {
}

