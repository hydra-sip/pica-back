package com.hydra.pica.plataforma_pica.partido.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** Los penales solo si el marcador termina empatado, y tienen que definir un ganador. */
public record ResultadoRequest(
        @NotNull @PositiveOrZero Integer puntajeLocal,
        @NotNull @PositiveOrZero Integer puntajeVisitante,
        @PositiveOrZero Integer penalesLocal,
        @PositiveOrZero Integer penalesVisitante) {
}
