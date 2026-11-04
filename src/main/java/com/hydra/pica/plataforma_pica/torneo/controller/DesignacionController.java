package com.hydra.pica.plataforma_pica.torneo.controller;

import java.util.List;

import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.torneo.dto.MiDesignacion;
import com.hydra.pica.plataforma_pica.torneo.service.DesignacionesService;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Las designaciones del árbitro que llama (HU-807, PICA-313). Sin permiso propio, como las objeciones: cada uno ve y
 * responde solo las suyas. Las reglas, en {@link DesignacionesService}.
 */
@RestController
@RequestMapping("/api/v1/designaciones")
public class DesignacionController {

    private final DesignacionesService designacionesService;
    private final CurrentUserProvider currentUserProvider;

    public DesignacionController(DesignacionesService designacionesService, CurrentUserProvider currentUserProvider) {
        this.designacionesService = designacionesService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/mias")
    public List<MiDesignacion> mias() {
        return designacionesService.mias(usuarioActual());
    }

    @PostMapping("/{torneoId}/aceptar")
    public MiDesignacion aceptar(@PathVariable Long torneoId) {
        return designacionesService.aceptar(usuarioActual(), torneoId);
    }

    @PostMapping("/{torneoId}/rechazar")
    public MiDesignacion rechazar(@PathVariable Long torneoId) {
        return designacionesService.rechazar(usuarioActual(), torneoId);
    }

    private Long usuarioActual() {
        return currentUserProvider.getCurrentUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
    }
}
