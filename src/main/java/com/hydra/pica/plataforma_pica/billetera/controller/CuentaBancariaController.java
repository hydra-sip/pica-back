package com.hydra.pica.plataforma_pica.billetera.controller;

import java.util.List;

import com.hydra.pica.plataforma_pica.billetera.dto.CrearCuentaBancariaRequest;
import com.hydra.pica.plataforma_pica.billetera.dto.CuentaBancariaResponse;
import com.hydra.pica.plataforma_pica.billetera.service.CuentaBancariaService;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cuentas bancarias y virtuales del usuario para retiros de fondos (HU-607, PICA-336).
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@RequestMapping("/api/v1/billetera/cuentas")
public class CuentaBancariaController {

    private final CuentaBancariaService cuentaBancariaService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping
    public List<CuentaBancariaResponse> listar() {
        return cuentaBancariaService.listar(usuarioActual());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CuentaBancariaResponse crear(@Valid @RequestBody CrearCuentaBancariaRequest request) {
        return cuentaBancariaService.crear(usuarioActual(), request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminar(@PathVariable Long id) {
        cuentaBancariaService.eliminar(usuarioActual(), id);
    }

    private Long usuarioActual() {
        return currentUserProvider.getCurrentUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
    }
}

