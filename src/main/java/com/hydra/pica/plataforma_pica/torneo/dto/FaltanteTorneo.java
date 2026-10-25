package com.hydra.pica.plataforma_pica.torneo.dto;

/**
 * Lo que le falta a un torneo para publicarse; va en {@code faltantes} del 409 TORNEO_INCOMPLETO (PICA-204), para que
 * el front muestre qué corregir y en qué paso del asistente.
 */
public enum FaltanteTorneo {
    /** Paso 1 incompleto: modalidad, tipo de participante, sede si es presencial, fechas o reglamento. */
    INFORMACION,
    /** Paso 2 incompleto: cupos y, por equipos, tamaño y nómina máxima. */
    CUPOS,
    /** Paso 3 sin valor de inscripción o sin el reparto. */
    ECONOMIA,
    /** No hay premios por puesto. */
    SIN_PREMIOS,
    /** Los premios no suman exactamente 100 %; la suma va en {@code sumaPremios}. */
    PREMIOS_NO_SUMAN_100,
    /** Con el cupo mínimo, los gastos previstos se comen el pozo de competidores. */
    POZO_NETO_NO_POSITIVO,
    /** El cierre de la inscripción ya pasó: hay que moverlo en el paso 1. */
    CIERRE_VENCIDO
}
