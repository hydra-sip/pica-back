package com.hydra.pica.plataforma_pica.billetera.event;

/**
 * Un torneo finalizado terminó de liquidarse y liberó su custodia (HU-606, usado por HU-804 para asegurar insignias
 * de podio y campeón tras la liquidación).
 */
public record TorneoLiquidado(Long torneoId, Long custodiaId) {
}
