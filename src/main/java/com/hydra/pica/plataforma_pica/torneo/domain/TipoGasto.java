package com.hydra.pica.plataforma_pica.torneo.domain;

/**
 * Qué es un gasto previsto (HU-604). Un honorario arbitral solo es deducible si se paga por la plataforma: el
 * organizador lo deposita en la custodia del torneo y la liquidación se lo acredita al árbitro.
 */
public enum TipoGasto {
    HONORARIO_ARBITRAL,
    OTRO
}
