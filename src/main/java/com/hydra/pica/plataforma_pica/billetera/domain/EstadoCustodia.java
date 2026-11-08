package com.hydra.pica.plataforma_pica.billetera.domain;

/** RETENIDA hasta que el evento termina; LIBERADA por la liquidación (HU-606) o REINTEGRADA si se canceló. */
public enum EstadoCustodia {
    RETENIDA,
    LIBERADA,
    REINTEGRADA
}
