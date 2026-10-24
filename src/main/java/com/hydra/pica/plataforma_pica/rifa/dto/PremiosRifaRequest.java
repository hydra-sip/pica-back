package com.hydra.pica.plataforma_pica.rifa.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Body de PUT /rifas/{id}/premios: la lista completa en orden de puesto (vacía = sin premios). */
public record PremiosRifaRequest(@NotNull @Valid List<@NotNull PremioRifaRequest> premios) {
}
