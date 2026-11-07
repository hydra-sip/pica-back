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
    ORGANIZADOR;

    /**
     * El de {@code usuarioId}, que ya se sabe que puede ver la inscripción: si no organiza el torneo, no es la
     * individual y no capitanea el equipo, figura en la nómina.
     */
    public static RolEnInscripcion de(Inscripcion inscripcion, Long usuarioId) {
        if (inscripcion.getTorneo().getOrganizador().getId().equals(usuarioId)) {
            return ORGANIZADOR;
        }
        if (!inscripcion.esDeEquipo()) {
            return INDIVIDUAL;
        }
        return inscripcion.getEquipo().getCapitan().getId().equals(usuarioId) ? CAPITAN : JUGADOR;
    }
}
