package com.hydra.pica.plataforma_pica.billetera.domain;

/** Un retiro a la wallet (HU-610): pendiente de cobro, cobrado en el contrato o devuelto a la billetera al vencer. */
public enum EstadoRetiroCripto {
    PENDIENTE,
    COBRADO,
    DEVUELTO
}
