package com.hydra.pica.plataforma_pica.billetera.controller;

import com.hydra.pica.plataforma_pica.billetera.dto.BilleteraResponse;
import com.hydra.pica.plataforma_pica.billetera.dto.MovimientoBilleteraResponse;
import com.hydra.pica.plataforma_pica.billetera.service.BilleteraService;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Billetera virtual del usuario (HU-602, T602-1).
 * Accesible únicamente para el usuario autenticado sobre sus propios fondos.
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@RequestMapping("/api/v1/billetera")
public class BilleteraController {

    private final BilleteraService billeteraService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping
    public BilleteraResponse verBilletera() {
        return billeteraService.obtenerBilletera(usuarioActual());
    }

    @GetMapping("/movimientos")
    public Page<MovimientoBilleteraResponse> listarMovimientos(@PageableDefault(size = 20) Pageable pageable) {
        return billeteraService.listarMovimientos(usuarioActual(), pageable);
    }

    private Long usuarioActual() {
        return currentUserProvider.getCurrentUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
    }
}

