package com.hydra.pica.plataforma_pica.user.dto;

import com.hydra.pica.plataforma_pica.user.domain.Rol;

public record RolMinimo(Long id, String nombre, String nombreAmigable) {

    public static RolMinimo desde(Rol rol) {
        return new RolMinimo(rol.getId(), rol.getNombre(), rol.getNombreAmigable());
    }
}