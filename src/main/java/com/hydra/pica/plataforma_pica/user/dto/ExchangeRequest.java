package com.hydra.pica.plataforma_pica.user.dto;

import jakarta.validation.constraints.NotBlank;

public record ExchangeRequest(
        @NotBlank(message = "REQUERIDO")
        String code
) {}
