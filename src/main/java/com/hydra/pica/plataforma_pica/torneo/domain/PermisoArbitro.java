package com.hydra.pica.plataforma_pica.torneo.domain;

/** Lo que el organizador delega en cada árbitro de su torneo (HU-203). */
public enum PermisoArbitro {
    /** Cargar y validar los resultados de los partidos. */
    CARGAR_RESULTADOS,
    /** Resolver las objeciones a un resultado. */
    RESOLVER_OBJECIONES
}
