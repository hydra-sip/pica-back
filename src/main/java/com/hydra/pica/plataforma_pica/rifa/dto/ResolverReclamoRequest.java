package com.hydra.pica.plataforma_pica.rifa.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Solicitud de resolución definitiva de un reclamo desde el panel administrativo (HU-706, PICA-339).
 */
public record ResolverReclamoRequest(
        @NotNull(message = "Falta la decisión")
        DecisionReclamo decision,
        @NotBlank(message = "Falta la resolución")
        String resolucion
) {
}

