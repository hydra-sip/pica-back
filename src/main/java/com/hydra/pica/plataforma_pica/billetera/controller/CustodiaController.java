package com.hydra.pica.plataforma_pica.billetera.controller;

import com.hydra.pica.plataforma_pica.billetera.dto.ComprobantePago;
import com.hydra.pica.plataforma_pica.billetera.dto.CustodiaResumen;
import com.hydra.pica.plataforma_pica.billetera.service.ComprobanteService;
import com.hydra.pica.plataforma_pica.billetera.service.CustodiaService;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * La custodia de los fondos (HU-603): el organizador ve la de su torneo o su rifa y no la puede retirar antes de la
 * liquidación; quien pagó una inscripción ve su comprobante.
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@RequestMapping("/api/v1")
public class CustodiaController {

    private final CustodiaService custodiaService;
    private final ComprobanteService comprobanteService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/torneos/{id}/custodia")
    public CustodiaResumen custodiaDelTorneo(@PathVariable Long id) {
        return custodiaService.resumenDelTorneo(usuarioActual(), id);
    }

    @GetMapping("/rifas/{id}/custodia")
    public CustodiaResumen custodiaDeLaRifa(@PathVariable Long id) {
        return custodiaService.resumenDeLaRifa(usuarioActual(), id);
    }

    /** Siempre 409: antes del cierre FONDOS_EN_CUSTODIA; después, la custodia la libera la liquidación. */
    @PostMapping("/torneos/{id}/custodia/retiro")
    public void retirarDelTorneo(@PathVariable Long id) {
        custodiaService.retirarDelTorneo(usuarioActual(), id);
    }

    @PostMapping("/rifas/{id}/custodia/retiro")
    public void retirarDeLaRifa(@PathVariable Long id) {
        custodiaService.retirarDeLaRifa(usuarioActual(), id);
    }

    @GetMapping("/inscripciones/{id}/comprobante")
    public ComprobantePago comprobanteDeInscripcion(@PathVariable Long id) {
        return comprobanteService.deInscripcion(usuarioActual(), id);
    }

    private Long usuarioActual() {
        return currentUserProvider.getCurrentUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
    }
}
