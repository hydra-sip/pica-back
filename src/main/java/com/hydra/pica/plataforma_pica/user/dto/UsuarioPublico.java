package com.hydra.pica.plataforma_pica.user.dto;

import com.hydra.pica.plataforma_pica.user.domain.Usuario;

/** Lo que cualquier usuario logueado puede ver de otro (UsuarioPublico en el contrato): sin email ni documento. */
public record UsuarioPublico(Long id, String username, String nombres, String apellidos) {

    public static UsuarioPublico desde(Usuario usuario) {
        return new UsuarioPublico(usuario.getId(), usuario.getUsername(), usuario.getPersona().getNombres(),
                usuario.getPersona().getApellidos());
    }
}
