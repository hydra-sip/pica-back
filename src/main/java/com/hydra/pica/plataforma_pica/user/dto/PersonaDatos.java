package com.hydra.pica.plataforma_pica.user.dto;

import java.time.LocalDate;

import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.Persona;

public record PersonaDatos(
        Long id,
        String nombres,
        String apellidos,
        String tipoDoc,
        String nroDoc,
        LocalDate fechaNacimiento,
        String domicilioPostal,
        String telefono,
        String descripcion,
        EstadoGeneral estado) {

    public static PersonaDatos desde(Persona persona) {
        return new PersonaDatos(
                persona.getId(),
                persona.getNombres(),
                persona.getApellidos(),
                persona.getTipoDoc(),
                persona.getNroDoc(),
                persona.getFechaNacimiento(),
                persona.getDomicilioPostal(),
                persona.getTelefono(),
                persona.getDescripcion(),
                persona.getEstado());
    }
}
