package com.hydra.pica.plataforma_pica.reputacion.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code POST /torneos/{id}/calificaciones}: a quién, de 1 a 5, un comentario opcional y si es no confiable. */
public record CalificacionRequest(
        @NotNull Long usuarioId,
        @NotNull @Min(1) @Max(5) Integer puntaje,
        @Size(max = 1000) String comentario,
        boolean noConfiable) {
}
