package com.hydra.pica.plataforma_pica.user.dto;

import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.Rol;

/** Fila del listado de roles (RolResumen en el contrato). */
public record RolResumen(
        Long id,
        String nombre,
        String nombreAmigable,
        String descripcion,
        EstadoGeneral estado,
        boolean esSistema,
        boolean eliminado,
        long cantidadUsuarios) {

    public static RolResumen de(Rol rol, long cantidadUsuarios) {
        return new RolResumen(rol.getId(), rol.getNombre(), rol.getNombreAmigable(), rol.getDescripcion(),
                rol.getEstado(), rol.isEsSistema(), rol.getEliminadoEn() != null, cantidadUsuarios);
    }
}
