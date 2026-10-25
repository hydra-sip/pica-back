package com.hydra.pica.plataforma_pica.torneo.dto;

import com.hydra.pica.plataforma_pica.user.domain.Usuario;

public record OrganizadorResumen(
        Long id,
        String nombreCompleto,
        String username) {

    public static OrganizadorResumen de(Usuario usuario) {
        if (usuario == null) {
            return null;
        }
        String nombreCompleto = usuario.getPersona() != null
                ? (usuario.getPersona().getNombres() + " " + usuario.getPersona().getApellidos()).trim()
                : usuario.getUsername();
        return new OrganizadorResumen(usuario.getId(), nombreCompleto, usuario.getUsername());
    }
}
