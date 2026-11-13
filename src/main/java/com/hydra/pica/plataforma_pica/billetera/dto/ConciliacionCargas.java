package com.hydra.pica.plataforma_pica.billetera.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Las cargas con cripto contra lo que registró el contrato en la cadena (HU-608, PICA-343). Se cruzan por la
 * referencia que se pagó. Primero las diferencias y después las conciliadas; dentro de cada grupo, por bloque.
 *
 * @param desdeBloque el del deploy: de ahí en adelante se leyeron los eventos
 * @param eventos     cuántos {@code CargaRegistrada} emitió el contrato
 */
public record ConciliacionCargas(
        Instant revisadaEn,
        long desdeBloque,
        int eventos,
        int cargasAcreditadas,
        int conciliadas,
        int huerfanas,
        int noAcreditadas,
        int montosDistintos,
        List<Registro> registros) {

    /**
     * CONCILIADA: acreditada y en la cadena por el mismo monto. HUERFANA: acreditada (con su movimiento CARGA) sin
     * evento en la cadena. NO_ACREDITADA: un pago en la cadena sin carga acreditada, de una carga pendiente o de una
     * referencia que no es nuestra. MONTO_DISTINTO: acreditada, pero la cadena registró otro monto.
     */
    public enum Estado { CONCILIADA, HUERFANA, NO_ACREDITADA, MONTO_DISTINTO }

    /**
     * Los montos en wei van como texto: no entran en un número de JavaScript.
     *
     * @param cargaId    null si la referencia no es de ninguna carga
     * @param username   quien inició la carga
     * @param montoWei   lo que la carga tenía que pagar
     * @param montoWeiEnCadena lo que registró el contrato; null si no hay evento
     * @param hash       la transacción con la que se acreditó la carga
     * @param hashEnCadena la transacción del evento
     */
    public record Registro(
            Estado estado,
            String referencia,
            Long cargaId,
            String username,
            Long movimientoId,
            BigDecimal montoPesos,
            String montoWei,
            String montoWeiEnCadena,
            String hash,
            String hashEnCadena,
            Long bloque,
            String pagador) {
    }
}
