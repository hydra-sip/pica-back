package com.hydra.pica.plataforma_pica.rifa.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Solicitud de reclamo sobre el resultado de una rifa (HU-506, T506-1).
 */
public record ReclamoRifaRequest(
        @NotBlank(message = "Falta el motivo")
        String motivo
) {
}
