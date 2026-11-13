package com.hydra.pica.plataforma_pica.rifa.controller;

import com.hydra.pica.plataforma_pica.common.dto.PaginaResponse;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoReclamo;
import com.hydra.pica.plataforma_pica.rifa.dto.ReclamoAdminDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.ResolverReclamoRequest;
import com.hydra.pica.plataforma_pica.rifa.service.LiberacionReclamoService;
import com.hydra.pica.plataforma_pica.rifa.service.ReclamoAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints administrativos para consultar reclamos de rifas, resolver disputas y liberar fondos (HU-706, HU-707).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/reclamos")
public class AdminReclamoController {

    private final ReclamoAdminService reclamoAdminService;
    private final LiberacionReclamoService liberacionReclamoService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping
    @PreAuthorize("hasAuthority('RECLAMO_RESOLVER')")
    public PaginaResponse<ReclamoAdminDetalle> listar(
            @RequestParam(required = false) EstadoReclamo estado,
            @RequestParam(required = false, defaultValue = "0") @Min(0) int page,
            @RequestParam(required = false, defaultValue = "20") @Min(1) @Max(100) int size) {
        return PaginaResponse.desde(reclamoAdminService.listar(estado, PageRequest.of(page, size)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('RECLAMO_RESOLVER')")
    public ReclamoAdminDetalle detalle(@PathVariable Long id) {
        return reclamoAdminService.detalle(id);
    }

    @PostMapping("/{id}/resolucion")
    @PreAuthorize("hasAuthority('RECLAMO_RESOLVER')")
    public ReclamoAdminDetalle resolver(
            @PathVariable Long id,
            @Valid @RequestBody ResolverReclamoRequest request) {
        Long resolutorId = currentUserProvider.getCurrentUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
        return reclamoAdminService.resolver(resolutorId, id, request);
    }

    @PostMapping("/{id}/liberar")
    @PreAuthorize("hasAuthority('RECLAMO_RESOLVER')")
    public ReclamoAdminDetalle liberar(@PathVariable Long id) {
        Long ejecutorId = currentUserProvider.getCurrentUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
        return liberacionReclamoService.liberarFondos(ejecutorId, id);
    }
}

