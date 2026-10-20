package com.hydra.pica.plataforma_pica.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReenviarVerificacionRequest(
        @NotBlank
        @Email
        @Size(max = 254)
        String email) {
}
