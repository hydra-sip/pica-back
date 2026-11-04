package com.hydra.pica.plataforma_pica.rifa.dto;

/**
 * Lo que le falta a una rifa para publicarse; va en {@code faltantes} del 409 RIFA_INCOMPLETA
 * (PICA-219) para que el front muestre qué corregir y en qué paso.
 */
public enum FaltantePublicacion {
    /** No están cargadas las fechas de venta. */
    VENTANA,
    /**
     * El fin de la venta ya pasó: hay que actualizarlo en el paso 1. Un inicio pasado sí se acepta: la venta
     * arranca al publicar (PICA-287).
     */
    VENTA_TERMINADA,
    /** No está cargada la fecha del sorteo. */
    FECHA_SORTEO,
    /** No hay premios. */
    SIN_PREMIOS,
    /**
     * {@code pctDestinadoTorneo} más los premios no suman exactamente 100 %; la suma de los premios (sin el
     * torneo) va en {@code sumaPremios}.
     */
    PREMIOS_NO_SUMAN_100,
    /** El torneo de la rifa todavía no se publicó (HU-401). */
    TORNEO_NO_PUBLICADO,
    /**
     * La venta empieza antes de que se publicara el torneo o termina después de su finalización (o de su inicio, si
     * el torneo no tiene fecha de fin) (HU-401).
     */
    VENTANA_FUERA_DEL_TORNEO
}
