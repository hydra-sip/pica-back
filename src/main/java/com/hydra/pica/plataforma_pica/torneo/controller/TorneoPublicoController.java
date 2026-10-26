package com.hydra.pica.plataforma_pica.torneo.controller;

import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoDetalle;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoResumen;
import com.hydra.pica.plataforma_pica.torneo.service.TorneoPublicoService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints públicos de torneos: catálogo y detalle (HU-208, T208-1).
 * Accesibles sin iniciar sesión.
 */
@RestController
@RequestMapping("/api/v1/torneos")
public class TorneoPublicoController {

    private final TorneoPublicoService torneoPublicoService;

    public TorneoPublicoController(TorneoPublicoService torneoPublicoService) {
        this.torneoPublicoService = torneoPublicoService;
    }

    @GetMapping("/resumen")
    public Page<TorneoResumen> listar(
            @RequestParam(required = false) String disciplina,
            @RequestParam(required = false) EstadoTorneo estado,
            @PageableDefault(size = 20) Pageable pageable) {
        return torneoPublicoService.listarPublicos(disciplina, estado, pageable);
    }

    @GetMapping("/slug/{slug}")
    public TorneoDetalle verPorSlug(@PathVariable String slug) {
        return torneoPublicoService.detallePublico(slug);
    }
}
