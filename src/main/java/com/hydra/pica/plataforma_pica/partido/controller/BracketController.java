package com.hydra.pica.plataforma_pica.partido.controller;

import java.net.URI;

import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.partido.dto.BracketTorneo;
import com.hydra.pica.plataforma_pica.partido.service.BracketService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * El bracket del torneo (HU-207): generarlo y regenerarlo es del organizador; verlo es público (PICA-216). Las reglas
 * están en {@link BracketService}.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/torneos/{id}/bracket")
public class BracketController {

    private final BracketService bracketService;
    private final CurrentUserProvider currentUserProvider;

    /** Sin sesión: lo ven los participantes y cualquiera que tenga el enlace del torneo. */
    @GetMapping
    public BracketTorneo ver(@PathVariable Long id) {
        return bracketService.ver(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('TORNEO_GESTIONAR')")
    public ResponseEntity<BracketTorneo> generar(@PathVariable Long id) {
        BracketTorneo bracket = bracketService.generar(usuarioActual(), id);
        URI ubicacion = ServletUriComponentsBuilder.fromCurrentRequest().build().toUri();
        return ResponseEntity.created(ubicacion).body(bracket);
    }

    @PostMapping("/regenerar")
    @PreAuthorize("hasAuthority('TORNEO_GESTIONAR')")
    public BracketTorneo regenerar(@PathVariable Long id) {
        return bracketService.regenerar(usuarioActual(), id);
    }

    private Long usuarioActual() {
        return currentUserProvider.getCurrentUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
    }
}
