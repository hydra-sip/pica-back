package com.hydra.pica.plataforma_pica.rifa.domain;

/** Un número que no tiene fila está libre (V13): no hay estado DISPONIBLE. */
public enum EstadoNumero {
    /** Elegido y sin pagar todavía; la reserva empieza en {@code creadoEn}. */
    RESERVADO,
    VENDIDO,
    REINTEGRADO
}
