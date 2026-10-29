package com.hydra.pica.plataforma_pica.realtime.dto;

import java.math.BigDecimal;
import java.util.List;

import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;

/**
 * Mensaje publicado en el topic /topic/rifas/{id} (HU-305, T305-1).
 *
 * @param tipo             tipo de evento: NUMEROS_VENDIDOS, ESTADO_CAMBIADO, SINCRONIZACION
 * @param rifaId           id de la rifa
 * @param torneoId         id del torneo vinculado (si la rifa pertenece a un torneo)
 * @param estado           estado actual de la rifa
 * @param numeros          números involucrados en esta venta
 * @param cantidadVendidos total acumulado de números vendidos
 * @param pozoRifa         lo que va a los premios de la rifa: lo vendido sin el aporte al torneo (HU-405)
 * @param aporteTorneo     monto que esta rifa aporta al pozo del torneo (si aplica)
 */
public record EventoRifaMessage(
        String tipo,
        Long rifaId,
        Long torneoId,
        EstadoRifa estado,
        List<Integer> numeros,
        Integer cantidadVendidos,
        BigDecimal pozoRifa,
        BigDecimal aporteTorneo
) {
}
