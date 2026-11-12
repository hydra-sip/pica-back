package com.hydra.pica.plataforma_pica.billetera.controller;

import java.util.UUID;

import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.dto.BilleteraResponse;
import com.hydra.pica.plataforma_pica.billetera.dto.CargaCriptoResponse;
import com.hydra.pica.plataforma_pica.billetera.dto.CargaRequest;
import com.hydra.pica.plataforma_pica.billetera.dto.ConfirmarCargaCriptoRequest;
import com.hydra.pica.plataforma_pica.billetera.dto.MovimientoBilleteraResponse;
import com.hydra.pica.plataforma_pica.billetera.dto.SolicitarRetiroRequest;
import com.hydra.pica.plataforma_pica.billetera.service.BilleteraService;
import com.hydra.pica.plataforma_pica.billetera.service.CargaCriptoService;
import com.hydra.pica.plataforma_pica.billetera.service.CargaService;
import com.hydra.pica.plataforma_pica.billetera.service.RetiroService;
import com.hydra.pica.plataforma_pica.common.config.CriptoConfig.CriptoProperties;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Billetera virtual del usuario (HU-602, T602-1, T602-2), con la carga pagada con la wallet (HU-609, PICA-333).
 * Accesible únicamente para el usuario autenticado sobre sus propios fondos.
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@RequestMapping("/api/v1/billetera")
public class BilleteraController {

    private final BilleteraService billeteraService;
    private final CargaService cargaService;
    private final CargaCriptoService cargaCriptoService;
    private final RetiroService retiroService;
    private final CriptoProperties cripto;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping
    public BilleteraResponse verBilletera() {
        return billeteraService.obtenerBilletera(usuarioActual());
    }

    @GetMapping("/movimientos")
    public Page<MovimientoBilleteraResponse> listarMovimientos(@PageableDefault(size = 20) Pageable pageable) {
        return billeteraService.listarMovimientos(usuarioActual(), pageable);
    }

    @PostMapping("/cargas")
    @ResponseStatus(HttpStatus.CREATED)
    public MovimientoBilleteraResponse cargarSaldo(@Valid @RequestBody CargaRequest request) {
        String referencia = "sim-carga-" + UUID.randomUUID();
        Movimiento movimiento = cargaService.acreditar(
                usuarioActual(),
                request.monto(),
                referencia,
                "Carga de saldo simulada");
        return MovimientoBilleteraResponse.de(movimiento, null, cripto.exploradorUrl());
    }

    @PostMapping("/cargas/cripto")
    @ResponseStatus(HttpStatus.CREATED)
    public CargaCriptoResponse iniciarCargaCripto(@Valid @RequestBody CargaRequest request) {
        return CargaCriptoResponse.de(cargaCriptoService.iniciar(usuarioActual(), request.monto()),
                cripto.contrato(), cripto.chainId());
    }

    @PostMapping("/cargas/cripto/{referencia}/confirmar")
    public MovimientoBilleteraResponse confirmarCargaCripto(@PathVariable String referencia,
                                                            @Valid @RequestBody ConfirmarCargaCriptoRequest request) {
        Movimiento movimiento = cargaCriptoService.confirmar(usuarioActual(), referencia, request.hash());
        return MovimientoBilleteraResponse.de(movimiento, null, cripto.exploradorUrl());
    }

    @PostMapping("/retiros")
    @ResponseStatus(HttpStatus.CREATED)
    public MovimientoBilleteraResponse solicitarRetiro(@Valid @RequestBody SolicitarRetiroRequest request) {
        Movimiento movimiento = retiroService.solicitar(usuarioActual(), request.cuentaId(), request.monto());
        return MovimientoBilleteraResponse.de(movimiento, null, cripto.exploradorUrl());
    }

    private Long usuarioActual() {
        return currentUserProvider.getCurrentUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
    }
}

