package com.hydra.pica.plataforma_pica.user.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Body de PUT /admin/usuarios/{id}/roles. Es la lista completa de ids: reemplaza a los roles que
 * tenía el usuario, y vacía lo deja sin roles. Los ids repetidos se toman una vez.
 */
public record RolesRequest(@NotNull List<@NotNull @Positive Long> roles) {
}
