package com.hydra.pica.plataforma_pica.rifa.event;

import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;

/**
 * Evento de dominio publicado cuando soporte ejecuta la liberación de fondos de un reclamo resuelto (HU-707).
 *
 * @param reclamoId       identificador del reclamo
 * @param rifaId          identificador de la rifa
 * @param torneoId        identificador del torneo asociado, o null si es independiente
 * @param rifaEstadoFinal estado definitivo de la rifa tras liberar ({@code LIQUIDADA} o {@code CANCELADA})
 */
public record FondosReclamoLiberados(
        Long reclamoId,
        Long rifaId,
        Long torneoId,
        EstadoRifa rifaEstadoFinal) {
}

