package com.hydra.pica.plataforma_pica.torneo.dto;

import java.util.Set;

import com.hydra.pica.plataforma_pica.torneo.domain.PermisoArbitro;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/** @param permisos al menos uno: sin permisos el árbitro no podría hacer nada (HU-203) */
public record DesignarArbitroRequest(@NotNull Long usuarioId, @NotEmpty Set<@NotNull PermisoArbitro> permisos) {
}
