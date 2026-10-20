package com.hydra.pica.plataforma_pica.user.dto;

import com.hydra.pica.plataforma_pica.user.domain.Persona;

public record PersonaUsuario(Long id, String nombreCompleto, String tipoDoc, String nroDoc) {

    public static PersonaUsuario desde(Persona persona) {
        String nombreCompleto = persona.getApellidos() + ", " + persona.getNombres();
        return new PersonaUsuario(persona.getId(), nombreCompleto, persona.getTipoDoc(), persona.getNroDoc());
    }
}