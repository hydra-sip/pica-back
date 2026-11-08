package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigDecimal;

import com.hydra.pica.plataforma_pica.billetera.domain.EstadoCustodia;

/**
 * Lo que salió de la custodia de un evento para repartirlo (HU-606) o reintegrarlo. Sin pagos, {@code custodiaId} es
 * null y los montos 0.
 */
public record LiberacionCustodia(Long custodiaId, EstadoCustodia estado, BigDecimal total, BigDecimal gastosAdministrativos) {
}
