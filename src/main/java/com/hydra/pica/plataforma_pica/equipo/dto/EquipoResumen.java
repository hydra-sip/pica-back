package com.hydra.pica.plataforma_pica.equipo.dto;

import com.hydra.pica.plataforma_pica.equipo.domain.Equipo;

/** Fila de "Mis equipos": lo justo para elegir el equipo al inscribirse. */
public record EquipoResumen(Long id, String nombre) {

    public static EquipoResumen de(Equipo equipo) {
        return new EquipoResumen(equipo.getId(), equipo.getNombre());
    }
}
