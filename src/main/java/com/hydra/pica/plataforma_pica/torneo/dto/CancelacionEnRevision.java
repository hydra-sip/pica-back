package com.hydra.pica.plataforma_pica.torneo.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Una cancelación en la cola de soporte (HU-605): el torneo, quién la pidió, por qué y los gastos a revisar. */
public record CancelacionEnRevision(Long torneoId, String torneoNombre, String torneoSlug,
                                    OrganizadorResumen organizador, String motivo, Instant pedidaEn, BigDecimal pozo,
                                    BigDecimal totalGastos, List<GastoEnRevision> gastos) {
}
