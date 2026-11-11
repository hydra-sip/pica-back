package com.hydra.pica.plataforma_pica.torneo.dto;

import java.math.BigDecimal;

/**
 * El resultado de cancelar un torneo (HU-605). {@code estado} es AUTOMATICA (se reintegró todo) o EN_REVISION (la plata
 * sigue en custodia y {@code reintegrado} es 0). {@code pozo} es lo recaudado en inscripciones.
 */
public record CancelacionRealizada(Long cancelacionId, Long torneoId, String estado, BigDecimal totalGastos,
                                   BigDecimal pozo, BigDecimal reintegrado) {
}
