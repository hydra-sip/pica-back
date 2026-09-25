package com.hydra.pica.plataforma_pica.user.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank String identificador,
        @NotBlank String password) {
}
