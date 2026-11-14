package com.hydra.pica.plataforma_pica.torneo.domain;

/** AUTOMATICA si se canceló sin gastos; con gastos, EN_REVISION hasta que soporte la aprueba o la rechaza (HU-605). */
public enum EstadoCancelacion {
    AUTOMATICA,
    EN_REVISION,
    APROBADA,
    RECHAZADA
}
