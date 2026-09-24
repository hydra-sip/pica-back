package com.hydra.pica.plataforma_pica.user.dto;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;

public record UsuarioDetalle(
        Long id,
        String username,
        String email,
        String descripcion,
        EstadoUsuario estado,
        boolean emailVerificado,
        boolean eliminado,
        Instant eliminadoEn,
        boolean protegido,
        boolean tieneContrasena,
        boolean conGoogle,
        PersonaDatos persona,
        List<RolMinimo> roles,
        Instant creadoEn,
        Instant modificadoEn) {

    public static UsuarioDetalle desde(Usuario usuario, Persona persona, boolean protegido) {
        List<RolMinimo> roles = usuario.getRoles().stream()
                .map(usuarioRol -> RolMinimo.desde(usuarioRol.getRol()))
                .sorted(Comparator.comparing(RolMinimo::nombre))
                .toList();

        return new UsuarioDetalle(
                usuario.getId(),
                usuario.getUsername(),
                usuario.getEmail(),
                usuario.getDescripcion(),
                usuario.getEstado(),
                usuario.isEmailVerificado(),
                usuario.getEliminadoEn() != null,
                usuario.getEliminadoEn(),
                protegido,
                usuario.getPasswordHash() != null,
                usuario.getGoogleSub() != null,
                PersonaDatos.desde(persona),
                roles,
                usuario.getCreadoEn(),
                usuario.getModificadoEn());
    }
}
