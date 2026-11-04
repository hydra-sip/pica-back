package com.hydra.pica.plataforma_pica.torneo.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * El paso 3 del asistente (HU-601, PICA-203). La inscripción va entera al torneo y los gastos administrativos los fija
 * la plataforma (PICA-283), así que el organizador no reparte nada; que los premios no sumen más de 100 lo valida
 * {@code EconomiaTorneoService}.
 *
 * @param premios el % del pozo de competidores de cada puesto, en orden (el primero es el 1.º); en BORRADOR pueden
 *                sumar menos de 100
 */
public record EconomiaTorneoRequest(
        @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal valorInscripcion,
        @NotNull List<@NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("100")
                @Digits(integer = 3, fraction = 2) BigDecimal> premios) {
}
