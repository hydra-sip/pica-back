package com.hydra.pica.plataforma_pica.rifa.domain;

public enum EstadoRifa {
    BORRADOR,
    EN_VENTA,
    CERRADA,
    SORTEADA,
    EN_REVISION,
    LIQUIDADA,
    CANCELADA;

    /** Todavía no completó el sorteo (edición, venta o cerrada esperando sortearse). */
    public boolean esPreSorteo() {
        return this == BORRADOR || this == EN_VENTA || this == CERRADA;
    }

    /** Estados posteriores al sorteo en los que puede registrarse o seguir abierto un reclamo. */
    public boolean admiteReclamos() {
        return this == SORTEADA || this == EN_REVISION;
    }

    /** Ciclo de vida cerrado definitivamente (fondos liquidados o rifa cancelada). */
    public boolean esTerminal() {
        return this == LIQUIDADA || this == CANCELADA;
    }
}
