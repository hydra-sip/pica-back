package com.hydra.pica.plataforma_pica.rifa.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Un premio de la rifa. El puesto no viene: es la posición en la lista (el primero es el 1.º).
 * El porcentaje es entero, de 1 a 100. Llega como BigDecimal para rechazar un 10.5 en vez de
 * truncarlo, que es lo que haría Jackson con un Integer.
 */
public record PremioRifaRequest(
        @NotNull @DecimalMin("1") @DecimalMax("100") @Digits(integer = 3, fraction = 0) BigDecimal pct,
        @Size(max = 255) String descripcion) {

    public int pctEntero() {
        return pct.intValueExact();
    }
}
