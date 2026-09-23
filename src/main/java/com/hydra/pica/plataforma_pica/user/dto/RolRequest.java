package com.hydra.pica.plataforma_pica.user.dto;

import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body de POST y PUT /admin/roles (RolRequest en el contrato). Los permisos no van acá: se
 * cargan con PUT /admin/roles/{id}/permisos (PICA-126).
 *
 * @param nombre técnico, es lo que viaja en el claim {@code roles} del JWT
 * @param estado en el alta, ACTIVO si no viene; en la modificación, si no viene se mantiene
 */
public record RolRequest(
        @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,49}$") String nombre,
        @NotBlank @Size(max = 100) String nombreAmigable,
        @Size(max = 500) String descripcion,
        EstadoGeneral estado) {
}
