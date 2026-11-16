package com.hydra.pica.plataforma_pica.reputacion.dto;

import com.hydra.pica.plataforma_pica.reputacion.domain.Gravedad;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code POST /torneos/{id}/sanciones}: a quién, con qué gravedad y por qué; el partido es opcional. */
public record SancionRequest(
        @NotNull Long usuarioId,
        Long partidoId,
        @NotNull Gravedad gravedad,
        @NotBlank @Size(max = 1000) String motivo) {
}
