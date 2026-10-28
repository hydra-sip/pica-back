package com.hydra.pica.plataforma_pica.rifa.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;

public record VentaRifaRequest(
        @NotEmpty(message = "Debe seleccionar al menos un número")
        List<Integer> numeros,
        String claveIdempotencia
) {
}
