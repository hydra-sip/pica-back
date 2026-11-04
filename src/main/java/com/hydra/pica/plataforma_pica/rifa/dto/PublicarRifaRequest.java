package com.hydra.pica.plataforma_pica.rifa.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/**
 * Body de POST /rifas/{id}/publicar (PICA-219): el organizador acepta los términos y declara que tiene
 * la autorización de la lotería provincial. Sin las dos en {@code true} no se publica (400).
 */
public record PublicarRifaRequest(
        @NotNull @AssertTrue Boolean aceptaTerminos,
        @NotNull @AssertTrue Boolean declaraAutorizacionLoteria) {
}
