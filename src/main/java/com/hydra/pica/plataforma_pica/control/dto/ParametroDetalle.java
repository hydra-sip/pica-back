package com.hydra.pica.plataforma_pica.control.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.hydra.pica.plataforma_pica.control.domain.ClaveParametro;

/**
 * Un parámetro de la plataforma en el admin (HU-702, PICA-282).
 *
 * @param modificadoPor el username de quien lo cambió por última vez, o {@code SISTEMA} si sigue el de la migración
 */
public record ParametroDetalle(
        String clave,
        ClaveParametro.Unidad unidad,
        BigDecimal valor,
        String descripcion,
        Instant modificadoEn,
        String modificadoPor) {
}
