package com.hydra.pica.plataforma_pica.user.controller;

import com.hydra.pica.plataforma_pica.common.security.UsuarioActual;
import com.hydra.pica.plataforma_pica.user.dto.CambioPasswordRequest;
import com.hydra.pica.plataforma_pica.user.dto.Me;
import com.hydra.pica.plataforma_pica.user.dto.MeUpdateRequest;
import com.hydra.pica.plataforma_pica.user.service.PerfilService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Perfil propio (PICA-121, PICA-122). Sin @PreAuthorize: cualquier usuario logueado ve y edita lo suyo,
 * y el "logueado" ya lo exige SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final PerfilService perfilService;

    public MeController(PerfilService perfilService) {
        this.perfilService = perfilService;
    }

    @GetMapping
    public Me obtener(@UsuarioActual Long usuarioId) {
        return perfilService.obtener(usuarioId);
    }

    @PutMapping
    public Me actualizar(@Valid @RequestBody MeUpdateRequest request, @UsuarioActual Long usuarioId) {
        return perfilService.actualizar(usuarioId, request);
    }

    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cambiarPassword(@Valid @RequestBody CambioPasswordRequest request, @UsuarioActual Long usuarioId) {
        perfilService.cambiarPassword(usuarioId, request);
    }
}
