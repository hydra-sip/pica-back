package com.hydra.pica.plataforma_pica.torneo.dto;

import jakarta.validation.constraints.Size;

/** Rechazar la designación (HU-807), con un motivo opcional que le llega al organizador. */
public record RechazarDesignacionRequest(@Size(max = 500) String motivo) {
}
