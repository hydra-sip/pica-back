package com.hydra.pica.plataforma_pica.inscripcion.domain;

/** Por qué se cerró la inscripción de un torneo (HU-206): queda en la auditoría del cierre. */
public enum MotivoCierre {
    /** La última confirmación completó el cupo. */
    CUPO,
    /** Llegó la fecha de cierre; lo cierra la tarea programada. */
    FECHA,
    /** Lo cerró el organizador antes de la fecha. */
    MANUAL
}
