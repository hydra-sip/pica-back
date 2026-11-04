package com.hydra.pica.plataforma_pica.torneo.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/**
 * Body de POST /torneos/{id}/publicar (PICA-204): el organizador acepta los términos de custodia de los fondos. Sin
 * eso en {@code true} no se publica (400).
 */
public record PublicarTorneoRequest(@NotNull @AssertTrue Boolean aceptaTerminos) {
}
