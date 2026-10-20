package com.hydra.pica.plataforma_pica.user.controller;

import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.user.dto.CambioPasswordRequest;
import com.hydra.pica.plataforma_pica.user.dto.Me;
import com.hydra.pica.plataforma_pica.user.dto.MeUpdateRequest;
import com.hydra.pica.plataforma_pica.user.service.PerfilService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
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
    private final CurrentUserProvider currentUserProvider;

    public MeController(PerfilService perfilService, CurrentUserProvider currentUserProvider) {
        this.perfilService = perfilService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public Me obtener() {
        return perfilService.obtener(usuarioActual());
    }

    @PutMapping
    public Me actualizar(@Valid @RequestBody MeUpdateRequest request) {
        return perfilService.actualizar(usuarioActual(), request);
    }

    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cambiarPassword(@Valid @RequestBody CambioPasswordRequest request) {
        perfilService.cambiarPassword(usuarioActual(), request);
    }

    /** Autenticado pero sin id en el principal: hasta que llegue el filtro JWT es siempre este caso. */
    private Long usuarioActual() {
        return currentUserProvider.getCurrentUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
    }
}
