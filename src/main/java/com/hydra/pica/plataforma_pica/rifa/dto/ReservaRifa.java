package com.hydra.pica.plataforma_pica.rifa.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Los números reservados mientras se paga (HU-403), con lo que se va a cobrar: {@code monto} (el precio de los
 * números, va al pozo de la rifa) más {@code gastosAdministrativos} es {@code total}. Pasado {@code venceEn} los
 * números vuelven a estar libres.
 */
public record ReservaRifa(
        Long rifaId,
        List<Integer> numeros,
        BigDecimal precioNumero,
        BigDecimal monto,
        BigDecimal pctGastosAdministrativos,
        BigDecimal gastosAdministrativos,
        BigDecimal total,
        Instant venceEn) {
}
