package com.hydra.pica.plataforma_pica.rifa.event;

import java.math.BigDecimal;
import java.util.List;

/**
 * Publicado al registrar la venta de números de una rifa (HU-305, T305-1).
 * Lo escucha el servicio de tiempo real para publicar en /topic/rifas/{id}
 * y en /topic/torneos/{id} si la rifa suma al pozo del torneo.
 *
 * @param rifaId           id de la rifa
 * @param torneoId         id del torneo vinculado (puede ser null)
 * @param numeros          números vendidos
 * @param compradorId      usuario que compró
 * @param montoTotal       monto total de la compra
 * @param pozoRifa         nuevo pozo acumulado de la rifa
 * @param pozoTorneo       nuevo pozo acumulado del torneo (si aplica)
 */
public record NumerosVendidos(
        Long rifaId,
        Long torneoId,
        List<Integer> numeros,
        Long compradorId,
        BigDecimal montoTotal,
        BigDecimal pozoRifa,
        BigDecimal pozoTorneo
) {
}
