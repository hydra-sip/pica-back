package com.hydra.pica.plataforma_pica.inscripcion.controller;

import java.net.URI;

import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionDetalle;
import com.hydra.pica.plataforma_pica.inscripcion.service.InscripcionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Inscripciones del usuario logueado (PICA-208). Crear y cancelar piden INSCRIPCION_GESTIONAR (V14, rol
 * PARTICIPANTE). Ver alcanza con estar logueado, porque también la ve el organizador del torneo: quién
 * la ve lo decide {@link InscripcionService}, y para cualquier otro responde 404.
 */
@RestController
@RequestMapping("/api/v1")
public class InscripcionController {

    private final InscripcionService inscripcionService;
    private final CurrentUserProvider currentUserProvider;

    public InscripcionController(InscripcionService inscripcionService, CurrentUserProvider currentUserProvider) {
        this.inscripcionService = inscripcionService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/torneos/{torneoId}/inscripciones")
    @PreAuthorize("hasAuthority('INSCRIPCION_GESTIONAR')")
    public ResponseEntity<InscripcionDetalle> crear(@PathVariable Long torneoId) {
        InscripcionDetalle creada = inscripcionService.crear(usuarioActual(), torneoId);
        URI ubicacion = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/inscripciones/{id}")
                .buildAndExpand(creada.id())
                .toUri();
        return ResponseEntity.created(ubicacion).body(creada);
    }

    @GetMapping("/inscripciones/{id}")
    public InscripcionDetalle ver(@PathVariable Long id) {
        return inscripcionService.detalle(usuarioActual(), id);
    }

    @DeleteMapping("/inscripciones/{id}")
    @PreAuthorize("hasAuthority('INSCRIPCION_GESTIONAR')")
    public ResponseEntity<Void> cancelar(@PathVariable Long id) {
        inscripcionService.cancelar(usuarioActual(), id);
        return ResponseEntity.noContent().build();
    }

    private Long usuarioActual() {
        return currentUserProvider.getCurrentUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
    }
}
