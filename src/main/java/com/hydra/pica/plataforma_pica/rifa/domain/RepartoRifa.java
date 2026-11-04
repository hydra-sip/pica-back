package com.hydra.pica.plataforma_pica.rifa.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Cómo se reparte lo vendido de una rifa (HU-405): el % que la rifa destina al torneo va a su pozo y el resto a los
 * premios. Los gastos administrativos no entran: los paga el comprador aparte. Se calcula sobre el total vendido y
 * los premios son la resta, así las dos partes suman siempre lo recaudado, sin diferencias de centavos.
 */
public record RepartoRifa(BigDecimal recaudado, BigDecimal alPozoTorneo, BigDecimal aPremios) {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    public static RepartoRifa de(Rifa rifa, long vendidos) {
        BigDecimal recaudado = rifa.getPrecioNumero().multiply(BigDecimal.valueOf(vendidos))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal alPozoTorneo = recaudado.multiply(BigDecimal.valueOf(rifa.getPctDestinadoTorneo()))
                .divide(CIEN, 2, RoundingMode.HALF_UP);
        return new RepartoRifa(recaudado, alPozoTorneo, recaudado.subtract(alPozoTorneo));
    }
}
