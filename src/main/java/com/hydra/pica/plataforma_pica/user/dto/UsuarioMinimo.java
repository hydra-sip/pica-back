package com.hydra.pica.plataforma_pica.user.dto;

import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;

/** El usuario vinculado que muestra la ficha de una persona (UsuarioMinimo en el contrato). */
public record UsuarioMinimo(Long id, String username, String email, EstadoUsuario estado, boolean eliminado) {

    public static UsuarioMinimo desde(Usuario usuario) {
        return new UsuarioMinimo(usuario.getId(), usuario.getUsername(), usuario.getEmail(), usuario.getEstado(),
                usuario.getEliminadoEn() != null);
    }
}
