package com.hydra.pica.plataforma_pica.inscripcion.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * El reparto del premio de un equipo entre los integrantes de su nómina (CR-05): el % que le toca a cada uno, con dos
 * decimales y sumando exactamente 100.
 */
public final class RepartoPremio {

    public static final BigDecimal CIEN = new BigDecimal("100.00");

    private RepartoPremio() {
    }

    /**
     * Partes iguales con dos decimales, redondeando hacia abajo; lo que sobra del redondeo va al capitán, esté donde
     * esté en la lista (tres integrantes: 33,34 para el capitán y 33,33 para los otros dos).
     *
     * @return el % de cada usuario, en el orden de {@code usuarioIds}
     */
    public static Map<Long, BigDecimal> partesIguales(List<Long> usuarioIds, Long capitanId) {
        if (usuarioIds.isEmpty() || !usuarioIds.contains(capitanId)) {
            throw new IllegalArgumentException("La nómina tiene que incluir al capitán");
        }
        BigDecimal parte = CIEN.divide(BigDecimal.valueOf(usuarioIds.size()), 2, RoundingMode.DOWN);
        BigDecimal sobrante = CIEN.subtract(parte.multiply(BigDecimal.valueOf(usuarioIds.size())));
        Map<Long, BigDecimal> reparto = new LinkedHashMap<>();
        for (Long usuarioId : usuarioIds) {
            reparto.put(usuarioId, usuarioId.equals(capitanId) ? parte.add(sobrante) : parte);
        }
        return reparto;
    }
}
