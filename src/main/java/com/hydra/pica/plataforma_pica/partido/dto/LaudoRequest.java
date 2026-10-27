package com.hydra.pica.plataforma_pica.partido.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * El laudo de un partido objetado (HU-304): el resultado oficial, con las reglas de la carga, la evidencia del partido
 * en la que se basa y por qué.
 */
public record LaudoRequest(
        @NotNull @PositiveOrZero Integer puntajeLocal,
        @NotNull @PositiveOrZero Integer puntajeVisitante,
        @PositiveOrZero Integer penalesLocal,
        @PositiveOrZero Integer penalesVisitante,
        @NotBlank @Size(max = 2000) String justificacion,
        @NotNull Long evidenciaId) {
}
