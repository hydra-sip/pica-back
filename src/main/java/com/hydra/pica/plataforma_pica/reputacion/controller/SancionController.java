package com.hydra.pica.plataforma_pica.reputacion.controller;

import java.net.URI;
import java.util.List;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.reputacion.dto.SancionDetalle;
import com.hydra.pica.plataforma_pica.reputacion.dto.SancionRequest;
import com.hydra.pica.plataforma_pica.reputacion.service.SancionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sanciones a jugadores (HU-803). Con sesión y sin permiso propio: quién puede sancionar (el organizador o un árbitro
 * del torneo) lo decide el servicio.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class SancionController {

    private final SancionService sancionService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping("/torneos/{id}/sanciones")
    public ResponseEntity<SancionDetalle> aplicar(@PathVariable Long id, @Valid @RequestBody SancionRequest request) {
        SancionDetalle sancion = sancionService.aplicar(usuarioActual(), id, request);
        return ResponseEntity.created(URI.create("/api/v1/torneos/" + id + "/sanciones")).body(sancion);
    }

    @GetMapping("/torneos/{id}/sanciones")
    public List<SancionDetalle> delTorneo(@PathVariable Long id) {
        return sancionService.delTorneo(id);
    }

    @GetMapping("/sanciones/mias")
    public List<SancionDetalle> mias() {
        return sancionService.mias(usuarioActual());
    }

    private Long usuarioActual() {
        return currentUserProvider.getCurrentUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
    }
}
