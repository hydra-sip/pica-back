package com.hydra.pica.plataforma_pica.torneo.dto;

import jakarta.validation.constraints.Size;

/**
 * Cómo se paga un gasto previsto (HU-604): un honorario se deposita para un árbitro designado ({@code arbitroId}); un
 * gasto común se registra con su {@code comprobante}. Va uno de los dos.
 */
public record PagoGastoRequest(Long arbitroId, @Size(max = 500) String comprobante) {
}
