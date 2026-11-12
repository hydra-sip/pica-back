package com.hydra.pica.plataforma_pica.torneo.dto;

import java.math.BigDecimal;

import com.hydra.pica.plataforma_pica.torneo.domain.TipoGasto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Un gasto previsto del paso 3 (PICA-203): se descuenta del pozo de competidores.
 *
 * @param tipo HONORARIO_ARBITRAL u OTRO (HU-604); sin el campo, OTRO
 */
public record GastoRequest(
        @NotBlank @Size(max = 255) String descripcion,
        @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal monto,
        TipoGasto tipo) {

    public GastoRequest(String descripcion, BigDecimal monto) {
        this(descripcion, monto, null);
    }
}
