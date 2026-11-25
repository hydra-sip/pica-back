package com.hydra.pica.plataforma_pica.reputacion.controller;

import java.net.URI;
import java.util.List;
import com.hydra.pica.plataforma_pica.common.security.UsuarioActual;
import com.hydra.pica.plataforma_pica.reputacion.dto.JugadorSancionable;
import com.hydra.pica.plataforma_pica.reputacion.dto.SancionDetalle;
import com.hydra.pica.plataforma_pica.reputacion.dto.SancionRequest;
import com.hydra.pica.plataforma_pica.reputacion.service.SancionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @PostMapping("/torneos/{id}/sanciones")
    public ResponseEntity<SancionDetalle> aplicar(@PathVariable Long id, @Valid @RequestBody SancionRequest request,
            @UsuarioActual Long usuarioId) {
        SancionDetalle sancion = sancionService.aplicar(usuarioId, id, request);
        return ResponseEntity.created(URI.create("/api/v1/torneos/" + id + "/sanciones")).body(sancion);
    }

    @GetMapping("/torneos/{id}/sanciones")
    public List<SancionDetalle> delTorneo(@PathVariable Long id) {
        return sancionService.delTorneo(id);
    }

    @GetMapping("/torneos/{id}/sanciones/jugadores")
    public List<JugadorSancionable> sancionables(@PathVariable Long id,
            @RequestParam(required = false) Long partidoId,
            @UsuarioActual Long usuarioId) {
        return sancionService.sancionables(usuarioId, id, partidoId);
    }

    @GetMapping("/sanciones/mias")
    public List<SancionDetalle> mias(@UsuarioActual Long usuarioId) {
        return sancionService.mias(usuarioId);
    }
}
