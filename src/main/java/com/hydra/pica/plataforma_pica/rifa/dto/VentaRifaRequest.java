package com.hydra.pica.plataforma_pica.rifa.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * Los números elegidos, para reservarlos y para comprarlos (HU-403). La clave de idempotencia de la compra va en el
 * header {@code Idempotency-Key}.
 */
public record VentaRifaRequest(@NotEmpty List<@NotNull Integer> numeros) {
}
