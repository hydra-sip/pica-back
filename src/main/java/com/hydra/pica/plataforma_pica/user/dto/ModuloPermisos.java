package com.hydra.pica.plataforma_pica.user.dto;

import java.util.List;

import com.hydra.pica.plataforma_pica.user.domain.Modulo;

/** Un módulo del catálogo de GET /admin/permisos, con sus permisos en el orden del seed de V4. */
public record ModuloPermisos(Modulo modulo, List<Item> permisos) {

    public record Item(String codigo, String descripcion) {
    }
}
