package com.hydra.pica.plataforma_pica.torneo.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Los gastos administrativos de la plataforma (HU-601, PICA-283): un % sobre el valor de la inscripción que paga aparte
 * quien se inscribe. La inscripción va entera al torneo; los gastos son de la plataforma.
 */
public final class GastosAdministrativos {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    private GastosAdministrativos() {
    }

    /** Lo que se paga de gastos por un valor; sin % (un torneo de antes de V27 en BORRADOR), 0. Null sin valor. */
    public static BigDecimal monto(BigDecimal valor, BigDecimal pct) {
        if (valor == null) {
            return null;
        }
        return pct == null ? BigDecimal.ZERO.setScale(2) : valor.multiply(pct).divide(CIEN, 2, RoundingMode.HALF_UP);
    }

    /** Valor más gastos: lo que paga quien se inscribe. Null sin valor. */
    public static BigDecimal total(BigDecimal valor, BigDecimal pct) {
        return valor == null ? null : valor.add(monto(valor, pct));
    }
}
