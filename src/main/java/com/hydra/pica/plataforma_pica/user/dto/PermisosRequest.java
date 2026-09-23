package com.hydra.pica.plataforma_pica.user.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Body de PUT /admin/roles/{id}/permisos. Es la lista completa: reemplaza a la que tenía el rol,
 * y vacía lo deja sin permisos. Los códigos repetidos se toman una vez.
 */
public record PermisosRequest(@NotNull List<@NotBlank String> permisos) {
}
