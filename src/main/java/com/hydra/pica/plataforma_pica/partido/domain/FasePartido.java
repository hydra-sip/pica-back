package com.hydra.pica.plataforma_pica.partido.domain;

/**
 * En qué parte del torneo se juega el partido (CR-02). En eliminación directa todos son de la llave. Los de grupos y
 * los de desempate son de un {@link Grupo}: en grupos la ronda es la fecha; en desempate, la ronda de su mini cuadro.
 */
public enum FasePartido {
    GRUPOS,
    DESEMPATE,
    ELIMINACION
}
