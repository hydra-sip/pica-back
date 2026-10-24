package com.hydra.pica.plataforma_pica.user.dto;

import java.time.Instant;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body de POST /admin/usuarios/{id}/bloqueo. Sin {@code hasta} el bloqueo es indefinido. */
public record BloqueoRequest(
        @NotBlank @Size(max = 500) String motivo,
        @Future Instant hasta) {
}
