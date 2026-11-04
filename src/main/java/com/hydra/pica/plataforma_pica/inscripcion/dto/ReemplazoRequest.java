package com.hydra.pica.plataforma_pica.inscripcion.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ReemplazoRequest(
        @NotNull @Positive Long saleUsuarioId,
        @NotNull @Positive Long entraUsuarioId) {
}
