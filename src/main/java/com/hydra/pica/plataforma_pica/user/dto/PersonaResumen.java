package com.hydra.pica.plataforma_pica.user.dto;

import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;

/** Fila del listado de personas (PersonaResumen en el contrato). */
public record PersonaResumen(
        Long id,
        String nombres,
        String apellidos,
        String tipoDoc,
        String nroDoc,
        EstadoGeneral estado,
        boolean eliminado,
        boolean tieneUsuario) {
}
