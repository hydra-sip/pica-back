package com.hydra.pica.plataforma_pica.user.dto;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;

public record UsuarioResumen(
        Long id,
        String username,
        String email,
        EstadoUsuario estado,
        boolean eliminado,
        boolean protegido,
        PersonaUsuario persona,
        List<RolMinimo> roles,
        Instant creadoEn) {

    public static UsuarioResumen desde(Usuario usuario, boolean protegido) {
        List<RolMinimo> roles = usuario.getRoles().stream()
                .map(usuarioRol -> RolMinimo.desde(usuarioRol.getRol()))
                .sorted(Comparator.comparing(RolMinimo::nombre))
                .toList();

        return new UsuarioResumen(
                usuario.getId(),
                usuario.getUsername(),
                usuario.getEmail(),
                usuario.getEstado(),
                usuario.getEliminadoEn() != null,
                protegido,
                PersonaUsuario.desde(usuario.getPersona()),
                roles,
                usuario.getCreadoEn());
    }
}
