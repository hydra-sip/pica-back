package com.hydra.pica.plataforma_pica.reputacion.controller;

import com.hydra.pica.plataforma_pica.reputacion.dto.PerfilOrganizador;
import com.hydra.pica.plataforma_pica.reputacion.service.PerfilOrganizadorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Perfil público del organizador (HU-806): accesible sin iniciar sesión desde el catálogo y la ficha del torneo. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class PerfilOrganizadorController {

    private final PerfilOrganizadorService perfilOrganizadorService;

    @GetMapping("/usuarios/{id}/perfil-organizador")
    public PerfilOrganizador ver(@PathVariable Long id) {
        return perfilOrganizadorService.ver(id);
    }
}
