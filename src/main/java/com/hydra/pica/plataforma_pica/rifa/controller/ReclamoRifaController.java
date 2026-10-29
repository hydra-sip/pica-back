package com.hydra.pica.plataforma_pica.rifa.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.rifa.dto.ReclamoRifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.ReclamoRifaRequest;
import com.hydra.pica.plataforma_pica.rifa.service.ReclamoRifaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Endpoints de reclamos sobre el resultado de rifas (HU-506, T506-1).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/rifas/{id}/reclamos")
public class ReclamoRifaController {

    private final ReclamoRifaService reclamoRifaService;
    private final CurrentUserProvider currentUserProvider;

    /**
     * Permite a un comprador disputar el resultado del sorteo de una rifa dentro de las 48 horas posteriores a su finalización.
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ReclamoRifaDetalle> registrarReclamo(
            @PathVariable Long id,
            @Valid @RequestBody ReclamoRifaRequest request) {
        Long usuarioId = currentUserProvider.getCurrentUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
        ReclamoRifaDetalle detalle = reclamoRifaService.registrarReclamo(usuarioId, id, request);
        URI ubicacion = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{reclamoId}")
                .buildAndExpand(detalle.id())
                .toUri();
        return ResponseEntity.created(ubicacion).body(detalle);
    }

    /**
     * Lista los reclamos registrados para una rifa.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<ReclamoRifaDetalle> listarReclamos(@PathVariable Long id) {
        return reclamoRifaService.listarPorRifa(id);
    }
}
