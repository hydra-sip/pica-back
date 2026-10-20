package com.hydra.pica.plataforma_pica.user.dto;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.Permiso;
import com.hydra.pica.plataforma_pica.user.domain.Rol;

/**
 * Un rol con sus permisos (RolDetalle en el contrato). En el contrato es RolResumen más estos
 * campos; acá va plano porque un record no puede extender a otro.
 *
 * @param permisos códigos en el orden del catálogo de V4, así el front no tiene que ordenarlos
 */
public record RolDetalle(
        Long id,
        String nombre,
        String nombreAmigable,
        String descripcion,
        EstadoGeneral estado,
        boolean esSistema,
        boolean eliminado,
        long cantidadUsuarios,
        List<String> permisos,
        Instant eliminadoEn,
        Instant creadoEn,
        Instant modificadoEn) {

    /** Recorre los permisos del rol: hay que llamarlo dentro de la transacción. */
    public static RolDetalle de(Rol rol, long cantidadUsuarios) {
        List<String> permisos = rol.getPermisos().stream()
                .sorted(Comparator.comparing(Permiso::getId))
                .map(Permiso::getCodigo)
                .toList();
        return new RolDetalle(rol.getId(), rol.getNombre(), rol.getNombreAmigable(), rol.getDescripcion(),
                rol.getEstado(), rol.isEsSistema(), rol.getEliminadoEn() != null, cantidadUsuarios,
                permisos, rol.getEliminadoEn(), rol.getCreadoEn(), rol.getModificadoEn());
    }
}
