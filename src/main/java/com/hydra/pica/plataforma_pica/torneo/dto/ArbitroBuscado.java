package com.hydra.pica.plataforma_pica.torneo.dto;

import com.hydra.pica.plataforma_pica.user.domain.Usuario;

/**
 * Un resultado del buscador de árbitros del paso 2: lo de {@code UsuarioPublico} y si tiene la identidad verificada,
 * que hace falta para arbitrar un torneo con dinero (HU-604).
 */
public record ArbitroBuscado(Long id, String username, String nombres, String apellidos, boolean identidadVerificada) {

    public static ArbitroBuscado desde(Usuario usuario) {
        return new ArbitroBuscado(usuario.getId(), usuario.getUsername(), usuario.getPersona().getNombres(),
                usuario.getPersona().getApellidos(), usuario.identidadVerificada());
    }
}
