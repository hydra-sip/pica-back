package com.hydra.pica.plataforma_pica.rifa.dto;

/**
 * Lo que le falta a una rifa para publicarse; va en {@code faltantes} del 409 RIFA_INCOMPLETA
 * (PICA-219) para que el front muestre qué corregir y en qué paso.
 */
public enum FaltantePublicacion {
    /** No están cargadas las fechas de venta. */
    VENTANA,
    /**
     * Las fechas de venta quedaron en el pasado: la venta empieza antes de ahora (o ya terminó). Hay que
     * actualizarlas en el paso 1; no se ajustan solas.
     */
    VENTA_YA_EMPEZO,
    /** No está cargada la fecha del sorteo. */
    FECHA_SORTEO,
    /** No hay premios. */
    SIN_PREMIOS,
    /** Los premios no suman exactamente 100 %; la suma va en {@code sumaPremios}. */
    PREMIOS_NO_SUMAN_100
}
