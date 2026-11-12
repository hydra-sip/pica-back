package com.hydra.pica.plataforma_pica.rifa.dto;

import com.hydra.pica.plataforma_pica.rifa.domain.EstadoReclamo;

/**
 * Decisión definitiva de soporte/administración al resolver un reclamo sobre una rifa (HU-706, PICA-339).
 */
public enum DecisionReclamo {
    SIN_FRAUDE(EstadoReclamo.RECHAZADO),
    FRAUDE_CONFIRMADO(EstadoReclamo.ACEPTADO);

    private final EstadoReclamo estadoResultante;

    DecisionReclamo(EstadoReclamo estadoResultante) {
        this.estadoResultante = estadoResultante;
    }

    public EstadoReclamo estadoResultante() {
        return estadoResultante;
    }
}

