package com.hydra.pica.plataforma_pica.billetera.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.hydra.pica.plataforma_pica.billetera.domain.EstadoMovimiento;

/**
 * Un pago de inscripción en el libro de ingresos del torneo (HU-608), wireframe «Dashboard Contable y Liquidación».
 *
 * @param comprobante          el número del comprobante de la inscripción (I{torneo}-{movimiento}), como lo ve quien
 *                             pagó
 * @param equipoNombre         null en un torneo individual
 * @param pagadorNombre        quien pagó: el jugador o, en equipos, el capitán
 * @param gastosAdministrativos lo que pagó aparte; va a la plataforma
 * @param estado               CONFIRMADO; REVERTIDO si se le devolvió (por ejemplo, al cancelarse el torneo)
 */
public record IngresoInscripcion(
        Long movimientoId,
        String comprobante,
        Instant pagadoEn,
        Long inscripcionId,
        String equipoNombre,
        String pagadorNombre,
        String pagadorUsername,
        BigDecimal monto,
        BigDecimal gastosAdministrativos,
        BigDecimal total,
        EstadoMovimiento estado) {
}
