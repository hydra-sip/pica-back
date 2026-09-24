package com.hydra.pica.plataforma_pica.user.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;

/** Ficha de una persona (PersonaDetalle en el contrato). {@code usuario} es null si no tiene. */
public record PersonaDetalle(
        Long id,
        String nombres,
        String apellidos,
        String tipoDoc,
        String nroDoc,
        LocalDate fechaNacimiento,
        String domicilioPostal,
        String telefono,
        String descripcion,
        EstadoGeneral estado,
        boolean eliminado,
        Instant eliminadoEn,
        Instant creadoEn,
        Instant modificadoEn,
        UsuarioMinimo usuario) {

    public static PersonaDetalle desde(Persona persona, Usuario usuario) {
        return new PersonaDetalle(
                persona.getId(),
                persona.getNombres(),
                persona.getApellidos(),
                persona.getTipoDoc(),
                persona.getNroDoc(),
                persona.getFechaNacimiento(),
                persona.getDomicilioPostal(),
                persona.getTelefono(),
                persona.getDescripcion(),
                persona.getEstado(),
                persona.getEliminadoEn() != null,
                persona.getEliminadoEn(),
                persona.getCreadoEn(),
                persona.getModificadoEn(),
                usuario == null ? null : UsuarioMinimo.desde(usuario));
    }
}
