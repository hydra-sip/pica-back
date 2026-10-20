package com.hydra.pica.plataforma_pica.user.controller;

import java.util.List;

import com.hydra.pica.plataforma_pica.user.dto.ModuloPermisos;
import com.hydra.pica.plataforma_pica.user.service.PermisoService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catálogo de permisos para la pantalla de roles (PICA-126). Es de solo lectura: los permisos se
 * cargan por migración y lo que se edita es qué permisos tiene cada rol, en {@link RolAdminController}.
 */
@RestController
@RequestMapping("/api/v1/admin/permisos")
@RequiredArgsConstructor
public class PermisoAdminController {

    private final PermisoService permisoService;

    @GetMapping
    @PreAuthorize("hasAuthority('ROL_VER')")
    public List<ModuloPermisos> catalogo() {
        return permisoService.catalogo();
    }
}
