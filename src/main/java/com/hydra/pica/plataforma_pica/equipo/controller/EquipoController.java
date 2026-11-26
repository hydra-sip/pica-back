package com.hydra.pica.plataforma_pica.equipo.controller;

import com.hydra.pica.plataforma_pica.common.security.UsuarioActual;
import com.hydra.pica.plataforma_pica.equipo.dto.EquipoResumen;
import com.hydra.pica.plataforma_pica.equipo.service.EquipoService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Equipos del usuario logueado (PICA-234). Las reglas (solo los que capitanea, sin los dados de baja) están en {@link EquipoService}. */
@RestController
@PreAuthorize("hasAuthority('INSCRIPCION_GESTIONAR')")
@RequestMapping("/api/v1/equipos")
public class EquipoController {

    private final EquipoService equipoService;

    public EquipoController(EquipoService equipoService) {
        this.equipoService = equipoService;
    }

    @GetMapping("/mios")
    public Page<EquipoResumen> mios(@PageableDefault(size = 20) Pageable pageable, @UsuarioActual Long usuarioId) {
        return equipoService.listarMios(usuarioId, pageable);
    }
}
