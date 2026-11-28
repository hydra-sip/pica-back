package com.hydra.pica.plataforma_pica.reputacion.domain;

/** Lo que mueve el índice de fair play (HU-802). */
public enum TipoEventoFairPlay {
    /** Un check-in cumplido: suma, sin pasar del máximo. */
    CHECKIN,
    /** No hizo check-in y el partido quedó en walkover o sin presentes: resta. */
    AUSENCIA,
    /** Una sanción (HU-803): resta según la gravedad. */
    SANCION
}
