package com.hydra.pica.plataforma_pica.torneo.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * El paso 3 del asistente (HU-601, PICA-203). Los tres % se mandan aunque la comisión sea el resto, para que el back
 * valide que suman 100 y que la comisión no pasa el tope de la plataforma; eso y que los premios no sumen más de 100
 * lo valida {@code EconomiaTorneoService}.
 *
 * @param premios el % del pozo de competidores de cada puesto, en orden (el primero es el 1.º); en BORRADOR pueden
 *                sumar menos de 100
 */
public record EconomiaTorneoRequest(
        @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal valorInscripcion,
        @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal pctPozoCompetidores,
        @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal pctPremioRifa,
        @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal pctComision,
        @NotNull List<@NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("100")
                @Digits(integer = 3, fraction = 2) BigDecimal> premios) {
}
