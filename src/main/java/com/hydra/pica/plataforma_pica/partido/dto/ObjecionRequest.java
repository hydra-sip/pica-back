package com.hydra.pica.plataforma_pica.partido.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Objetar el resultado cargado (HU-302): por qué y cuál fue para quien objeta. El resultado sigue las reglas de la
 * carga; la evidencia se sube antes, por {@code POST /partidos/{id}/evidencias}.
 */
public record ObjecionRequest(
        @NotBlank @Size(max = 1000) String motivo,
        @NotNull @PositiveOrZero Integer puntajeLocal,
        @NotNull @PositiveOrZero Integer puntajeVisitante,
        @PositiveOrZero Integer penalesLocal,
        @PositiveOrZero Integer penalesVisitante) {
}
