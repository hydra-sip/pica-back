package com.hydra.pica.plataforma_pica.equipo.controller;

import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.equipo.dto.EquipoResumen;
import com.hydra.pica.plataforma_pica.equipo.service.EquipoService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Equipos del usuario logueado (PICA-234). Las reglas (solo los que capitanea, sin los dados de baja) están en {@link EquipoService}. */
@RestController
@PreAuthorize("hasAuthority('INSCRIPCION_GESTIONAR')")
@RequestMapping("/api/v1/equipos")
public class EquipoController {

    private final EquipoService equipoService;
    private final CurrentUserProvider currentUserProvider;

    public EquipoController(EquipoService equipoService, CurrentUserProvider currentUserProvider) {
        this.equipoService = equipoService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/mios")
    public Page<EquipoResumen> mios(@PageableDefault(size = 20) Pageable pageable) {
        return equipoService.listarMios(usuarioActual(), pageable);
    }

    private Long usuarioActual() {
        return currentUserProvider.getCurrentUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
    }
}
