package com.hydra.pica.plataforma_pica.inscripcion.domain;

/**
 * Con qué rol participa en una inscripción el usuario que la consulta (HU-209). El organizador del torneo solo
 * aparece al ver el detalle: no puede inscribirse en su propio torneo, así que nunca se superpone con los demás.
 */
public enum RolEnInscripcion {
    /** El capitán del equipo inscripto. */
    CAPITAN,
    /** Figura en la nómina del equipo y no es su capitán. */
    JUGADOR,
    /** El participante de una inscripción individual. */
    INDIVIDUAL,
    /** El organizador del torneo. */
    ORGANIZADOR
}
