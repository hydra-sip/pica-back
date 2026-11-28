package com.hydra.pica.plataforma_pica.partido.controller;

import com.hydra.pica.plataforma_pica.partido.dto.GruposTorneo;
import com.hydra.pica.plataforma_pica.partido.dto.SorteoGruposDetalle;
import com.hydra.pica.plataforma_pica.partido.service.SorteoGruposService;
import com.hydra.pica.plataforma_pica.partido.service.TablaGruposService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * La fase de grupos del torneo (CR-02), pública: las tablas y el sorteo para verificarlo. Los grupos se sortean al
 * generar el bracket.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/torneos/{id}")
public class GruposController {

    private final SorteoGruposService sorteoGruposService;
    private final TablaGruposService tablaGruposService;

    /** Sin sesión: los grupos, sus tablas con el criterio de cada puesto, y sus partidos. */
    @GetMapping("/grupos")
    public GruposTorneo grupos(@PathVariable Long id) {
        return tablaGruposService.ver(id);
    }

    /** Sin sesión, para el portal de verificación. */
    @GetMapping("/sorteo-grupos")
    public SorteoGruposDetalle sorteo(@PathVariable Long id) {
        return sorteoGruposService.ver(id);
    }
}
