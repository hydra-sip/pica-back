package com.hydra.pica.plataforma_pica.rifa.event;

import java.math.BigDecimal;
import java.util.Map;

import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;

/**
 * Evento de dominio publicado cuando soporte ejecuta la liberación de fondos de un reclamo resuelto (HU-707).
 *
 * @param reclamoId       identificador del reclamo
 * @param rifaId          identificador de la rifa
 * @param torneoId        identificador del torneo asociado, o null si es independiente
 * @param rifaEstadoFinal estado definitivo de la rifa tras liberar ({@code LIQUIDADA} o {@code CANCELADA})
 * @param acreditados     cuánto se le acreditó a cada usuario (por id): ganadores y organizador si se liquidó,
 *                        cada comprador si se reintegró; para avisarle a cada uno su monto (HU-808)
 */
public record FondosReclamoLiberados(
        Long reclamoId,
        Long rifaId,
        Long torneoId,
        EstadoRifa rifaEstadoFinal,
        Map<Long, BigDecimal> acreditados) {
}
