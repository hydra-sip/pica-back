package com.hydra.pica.plataforma_pica.torneo.domain;

/**
 * Qué pasa si al vencer la ventana de check-in no se presentó ninguno de los dos (HU-309). DESCALIFICAR_AMBOS se
 * aplica como REPROGRAMAR en la final y si el partido hermano ya terminó sin ganador.
 */
public enum CriterioSinPresentes {
    /** El partido queda sin fecha, para que el organizador le ponga una nueva. */
    REPROGRAMAR,
    /** El partido queda WALKOVER sin ganador: ninguno de los dos pasa de ronda. */
    DESCALIFICAR_AMBOS
}
