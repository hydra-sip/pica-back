package com.hydra.pica.plataforma_pica.notificacion.domain;

/** Los eventos de HU-808 que le llegan a un usuario. Uno nuevo suma su valor acá y en el CHECK de la tabla. */
public enum TipoNotificacion {
    RIVAL_ASIGNADO,
    CHECKIN_ABIERTO,
    CRUCE_INICIADO,
    RECLAMO_RESUELTO
}
