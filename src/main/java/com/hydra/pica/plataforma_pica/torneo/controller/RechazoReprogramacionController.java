package com.hydra.pica.plataforma_pica.torneo.controller;

import com.hydra.pica.plataforma_pica.common.security.UsuarioActual;
import com.hydra.pica.plataforma_pica.torneo.dto.RechazoReprogramacion;
import com.hydra.pica.plataforma_pica.torneo.service.RechazoReprogramacionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Rechazar la nueva fecha de un torneo reprogramado (CR-01-3). Las reglas están en {@link RechazoReprogramacionService}. */
@RestController
public class RechazoReprogramacionController {

    private final RechazoReprogramacionService rechazoReprogramacionService;

    public RechazoReprogramacionController(RechazoReprogramacionService rechazoReprogramacionService) {
        this.rechazoReprogramacionService = rechazoReprogramacionService;
    }

    @PostMapping("/api/v1/inscripciones/{id}/rechazo-reprogramacion")
    @PreAuthorize("hasAuthority('INSCRIPCION_GESTIONAR')")
    public RechazoReprogramacion rechazar(@PathVariable Long id, @UsuarioActual Long usuarioId) {
        return rechazoReprogramacionService.rechazar(usuarioId, id);
    }
}
