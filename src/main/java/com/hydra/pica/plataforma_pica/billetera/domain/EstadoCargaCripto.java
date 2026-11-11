package com.hydra.pica.plataforma_pica.billetera.domain;

/** Un intento rechazado no cambia el estado: la carga sigue esperando la transacción que la pague. */
public enum EstadoCargaCripto {
    PENDIENTE,
    ACREDITADA
}
