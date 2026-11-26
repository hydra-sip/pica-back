package com.hydra.pica.plataforma_pica.torneo.dto;

import java.math.BigDecimal;

/**
 * El resultado de rechazar la nueva fecha de un torneo (CR-01-3): la inscripción quedó REINTEGRADA y {@code reintegrado}
 * volvió al disponible de quien pagó (sin los gastos administrativos).
 */
public record RechazoReprogramacion(Long inscripcionId, Long torneoId, String estado, BigDecimal reintegrado) {
}
