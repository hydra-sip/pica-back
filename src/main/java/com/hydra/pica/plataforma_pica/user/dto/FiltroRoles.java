package com.hydra.pica.plataforma_pica.user.dto;

import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;

import jakarta.validation.constraints.Size;

/**
 * Query params de GET /admin/roles. Va como record y no como @RequestParam sueltos para que un
 * {@code estado} que no existe o un {@code q} largo salgan como 400 VALIDACION con el campo,
 * igual que los errores de un body.
 */
public record FiltroRoles(
        @Size(max = 100) String q,
        EstadoGeneral estado,
        Boolean incluirEliminados) {

    public boolean conEliminados() {
        return Boolean.TRUE.equals(incluirEliminados);
    }
}
