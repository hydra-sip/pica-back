package com.hydra.pica.plataforma_pica.rifa.controller;

import jakarta.validation.Valid;

import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.rifa.dto.VentaRifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.VentaRifaResultado;
import com.hydra.pica.plataforma_pica.rifa.service.VentaRifaService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Compra / venta de números de rifa (HU-305, T305-1).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/rifas/{id}")
public class VentaRifaController {

    private final VentaRifaService ventaRifaService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping("/comprar")
    @PreAuthorize("isAuthenticated()")
    public VentaRifaResultado comprar(@PathVariable Long id, @Valid @RequestBody VentaRifaRequest request) {
        Long usuarioId = currentUserProvider.getCurrentUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
        return ventaRifaService.vender(usuarioId, id, request.numeros(), request.claveIdempotencia());
    }
}
