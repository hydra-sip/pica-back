package com.hydra.pica.plataforma_pica.torneo.dto;

import java.math.BigDecimal;

/**
 * La decisión de soporte sobre una cancelación con gastos (HU-605): {@code estado} APROBADA o RECHAZADA, lo que volvió
 * a las billeteras de quienes pagaron, lo que se retuvo de los gastos y lo que cobró el organizador.
 */
public record ResolucionCancelacion(Long torneoId, String estado, BigDecimal reintegrado, BigDecimal retenido,
                                    BigDecimal alOrganizador) {
}
