package com.hydra.pica.plataforma_pica.user.dto;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;

/**
 * Respuesta de GET y PUT /me (schema Me del contrato). El front la pide apenas tiene token:
 * con {@code permisos} arma el menú y con {@code datosCompletos} decide el banner "Completá tus datos".
 */
public record Me(
        DatosUsuario usuario,
        PersonaDatos persona,
        List<RolMinimo> roles,
        List<String> permisos,
        boolean datosCompletos) {

    public record DatosUsuario(
            Long id,
            String username,
            String email,
            EstadoUsuario estado,
            boolean tieneContrasena) {
    }

    public static Me desde(Usuario usuario, Collection<String> permisos) {
        List<RolMinimo> roles = usuario.getRoles().stream()
                .map(usuarioRol -> RolMinimo.desde(usuarioRol.getRol()))
                .sorted(Comparator.comparing(RolMinimo::nombre))
                .toList();

        return new Me(
                new DatosUsuario(
                        usuario.getId(),
                        usuario.getUsername(),
                        usuario.getEmail(),
                        usuario.getEstado(),
                        usuario.getPasswordHash() != null),
                PersonaDatos.desde(usuario.getPersona()),
                roles,
                permisos.stream().sorted().toList(),
                datosCompletos(usuario.getPersona()));
    }

    /** Documento, fecha de nacimiento, domicilio y teléfono. Un usuario de Google arranca sin ninguno. */
    static boolean datosCompletos(Persona persona) {
        return persona.getTipoDoc() != null
                && tieneTexto(persona.getNroDoc())
                && persona.getFechaNacimiento() != null
                && tieneTexto(persona.getDomicilioPostal())
                && tieneTexto(persona.getTelefono());
    }

    private static boolean tieneTexto(String valor) {
        return valor != null && !valor.isBlank();
    }
}
