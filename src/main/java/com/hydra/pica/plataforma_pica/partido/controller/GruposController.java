package com.hydra.pica.plataforma_pica.partido.controller;

import com.hydra.pica.plataforma_pica.partido.dto.SorteoGruposDetalle;
import com.hydra.pica.plataforma_pica.partido.service.SorteoGruposService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** La fase de grupos del torneo (CR-02), pública: el sorteo para verificarlo. Se sortea al generar el bracket. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/torneos/{id}")
public class GruposController {

    private final SorteoGruposService sorteoGruposService;

    /** Sin sesión, para el portal de verificación. */
    @GetMapping("/sorteo-grupos")
    public SorteoGruposDetalle sorteo(@PathVariable Long id) {
        return sorteoGruposService.ver(id);
    }
}
