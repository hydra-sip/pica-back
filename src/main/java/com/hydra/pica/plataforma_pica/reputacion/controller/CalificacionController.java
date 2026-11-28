package com.hydra.pica.plataforma_pica.reputacion.controller;

import java.net.URI;
import java.util.List;
import com.hydra.pica.plataforma_pica.common.security.UsuarioActual;
import com.hydra.pica.plataforma_pica.reputacion.dto.CalificacionDetalle;
import com.hydra.pica.plataforma_pica.reputacion.dto.CalificacionRequest;
import com.hydra.pica.plataforma_pica.reputacion.dto.JugadorCalificable;
import com.hydra.pica.plataforma_pica.reputacion.service.CalificacionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Calificaciones de jugadores (HU-805). Con sesión y sin permiso propio: que sea el organizador del torneo lo decide el
 * servicio.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class CalificacionController {

    private final CalificacionService calificacionService;

    @PostMapping("/torneos/{id}/calificaciones")
    public ResponseEntity<CalificacionDetalle> calificar(@PathVariable Long id,
            @Valid @RequestBody CalificacionRequest request,
            @UsuarioActual Long usuarioId) {
        CalificacionDetalle calificacion = calificacionService.calificar(usuarioId, id, request);
        return ResponseEntity.created(URI.create("/api/v1/torneos/" + id + "/calificaciones")).body(calificacion);
    }

    @GetMapping("/torneos/{id}/calificaciones")
    public List<JugadorCalificable> calificables(@PathVariable Long id, @UsuarioActual Long usuarioId) {
        return calificacionService.calificables(usuarioId, id);
    }
}
