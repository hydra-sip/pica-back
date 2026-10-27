package com.hydra.pica.plataforma_pica.partido.controller;

import java.util.List;

import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.partido.dto.ObjecionResumen;
import com.hydra.pica.plataforma_pica.partido.service.ObjecionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * La vista consolidada de los partidos objetados (HU-303, PICA-246). Con sesión y sin permiso propio: el organizador
 * o un árbitro con permiso para resolver objeciones, como en el laudo; lo decide el servicio.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class ObjecionController {

    private final ObjecionService objecionService;
    private final CurrentUserProvider currentUserProvider;

    /** Las pendientes primero; las evidencias de cada una, en {@code GET /partidos/{id}/objecion}. */
    @GetMapping("/torneos/{id}/objeciones")
    public List<ObjecionResumen> delTorneo(@PathVariable Long id) {
        return objecionService.delTorneo(usuarioActual(), id);
    }

    private Long usuarioActual() {
        return currentUserProvider.getCurrentUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
    }
}
