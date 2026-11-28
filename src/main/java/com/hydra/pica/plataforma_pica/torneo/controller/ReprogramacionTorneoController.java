package com.hydra.pica.plataforma_pica.torneo.controller;

import java.util.List;

import com.hydra.pica.plataforma_pica.common.security.UsuarioActual;
import com.hydra.pica.plataforma_pica.torneo.dto.ReprogramacionDetalle;
import com.hydra.pica.plataforma_pica.torneo.dto.ReprogramacionRequest;
import com.hydra.pica.plataforma_pica.torneo.service.ReprogramacionTorneoService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reprogramar un torneo y su historial (CR-01, PICA-391). Las reglas están en {@link ReprogramacionTorneoService}. El
 * historial es público, como la ficha.
 */
@RestController
@RequestMapping("/api/v1/torneos/{id}/reprogramaciones")
public class ReprogramacionTorneoController {

    private final ReprogramacionTorneoService reprogramacionTorneoService;

    public ReprogramacionTorneoController(ReprogramacionTorneoService reprogramacionTorneoService) {
        this.reprogramacionTorneoService = reprogramacionTorneoService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('TORNEO_GESTIONAR')")
    @ResponseStatus(HttpStatus.CREATED)
    public ReprogramacionDetalle reprogramar(@PathVariable Long id, @RequestBody ReprogramacionRequest request,
            @UsuarioActual Long usuarioId) {
        return reprogramacionTorneoService.reprogramar(usuarioId, id, request);
    }

    @GetMapping
    public List<ReprogramacionDetalle> historial(@PathVariable Long id) {
        return reprogramacionTorneoService.historial(id);
    }
}
