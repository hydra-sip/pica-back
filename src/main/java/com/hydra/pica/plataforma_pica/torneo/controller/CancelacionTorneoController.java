package com.hydra.pica.plataforma_pica.torneo.controller;

import java.util.List;

import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.torneo.dto.CancelacionRealizada;
import com.hydra.pica.plataforma_pica.torneo.dto.SolicitudCancelacion;
import com.hydra.pica.plataforma_pica.torneo.service.CancelacionTorneoService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** El organizador cancela su torneo (HU-605, PICA-347). Las reglas están en {@link CancelacionTorneoService}. */
@RestController
@PreAuthorize("hasAuthority('TORNEO_GESTIONAR')")
@RequestMapping("/api/v1/torneos/{id}/cancelacion")
public class CancelacionTorneoController {

    private final CancelacionTorneoService cancelacionTorneoService;
    private final CurrentUserProvider currentUserProvider;

    public CancelacionTorneoController(CancelacionTorneoService cancelacionTorneoService,
            CurrentUserProvider currentUserProvider) {
        this.cancelacionTorneoService = cancelacionTorneoService;
        this.currentUserProvider = currentUserProvider;
    }

    /** Multipart: {@code motivo}. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CancelacionRealizada cancelar(@PathVariable Long id,
            @RequestParam(name = "motivo", required = false) String motivo) {
        return cancelacionTorneoService.cancelar(usuarioActual(), id, new SolicitudCancelacion(motivo, List.of()));
    }

    private Long usuarioActual() {
        return currentUserProvider.getCurrentUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
    }
}
