package com.hydra.pica.plataforma_pica.notificacion.domain;

/** Los eventos que le llegan a un usuario (HU-808, CR-01-4). Uno nuevo suma su valor acá y en el CHECK de la tabla. */
public enum TipoNotificacion {
    RIVAL_ASIGNADO,
    CHECKIN_ABIERTO,
    CRUCE_INICIADO,
    RECLAMO_RESUELTO,
    /** A los inscriptos: la nueva fecha y hasta cuándo pueden bajarse (CR-01-4). */
    TORNEO_REPROGRAMADO,
    /** A los que siguen inscriptos, un día antes de que venza el plazo para bajarse (CR-01-4). */
    REPROGRAMACION_POR_VENCER,
    /** Al organizador, cuando con las bajas no llega al mínimo para jugar (CR-01-4). */
    INSCRIPTOS_BAJO_EL_MINIMO,
    /** Al organizador, cuando vence el plazo: cuántos se bajaron y cómo quedó la inscripción (CR-01-4). */
    REPROGRAMACION_RESUELTA
}
