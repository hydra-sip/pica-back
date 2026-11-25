package com.hydra.pica.plataforma_pica.billetera.controller;

import java.util.UUID;

import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.dto.BilleteraResponse;
import com.hydra.pica.plataforma_pica.billetera.dto.CargaCriptoResponse;
import com.hydra.pica.plataforma_pica.billetera.dto.CargaRequest;
import com.hydra.pica.plataforma_pica.billetera.dto.ConfirmarCargaCriptoRequest;
import com.hydra.pica.plataforma_pica.billetera.dto.LimitesRetiroResponse;
import com.hydra.pica.plataforma_pica.billetera.dto.MovimientoBilleteraResponse;
import com.hydra.pica.plataforma_pica.billetera.dto.SolicitarRetiroRequest;
import com.hydra.pica.plataforma_pica.billetera.service.BilleteraService;
import com.hydra.pica.plataforma_pica.billetera.service.CargaCriptoService;
import com.hydra.pica.plataforma_pica.billetera.service.CargaService;
import com.hydra.pica.plataforma_pica.billetera.service.RetiroService;
import com.hydra.pica.plataforma_pica.common.config.CriptoConfig.CriptoProperties;
import com.hydra.pica.plataforma_pica.common.security.UsuarioActual;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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

    @GetMapping
    public BilleteraResponse verBilletera(@UsuarioActual Long usuarioId) {
        return billeteraService.obtenerBilletera(usuarioId);
    }

    @GetMapping("/movimientos")
    public Page<MovimientoBilleteraResponse> listarMovimientos(@PageableDefault(size = 20) Pageable pageable,
            @UsuarioActual Long usuarioId) {
        return billeteraService.listarMovimientos(usuarioId, pageable);
    }

    @PostMapping("/cargas")
    @ResponseStatus(HttpStatus.CREATED)
    public MovimientoBilleteraResponse cargarSaldo(@Valid @RequestBody CargaRequest request,
            @UsuarioActual Long usuarioId) {
        String referencia = "sim-carga-" + UUID.randomUUID();
        Movimiento movimiento = cargaService.acreditar(
                usuarioId,
                request.monto(),
                referencia,
                "Carga de saldo simulada");
        return MovimientoBilleteraResponse.de(movimiento, null, cripto.exploradorUrl());
    }

    @PostMapping("/cargas/cripto")
    @ResponseStatus(HttpStatus.CREATED)
    public CargaCriptoResponse iniciarCargaCripto(@Valid @RequestBody CargaRequest request,
            @UsuarioActual Long usuarioId) {
        return CargaCriptoResponse.de(cargaCriptoService.iniciar(usuarioId, request.monto()),
                cripto.contrato(), cripto.chainId());
    }

    @PostMapping("/cargas/cripto/{referencia}/confirmar")
    public MovimientoBilleteraResponse confirmarCargaCripto(@PathVariable String referencia,
                                                            @Valid @RequestBody ConfirmarCargaCriptoRequest request,
                                                            @UsuarioActual Long usuarioId) {
        Movimiento movimiento = cargaCriptoService.confirmar(usuarioId, referencia, request.hash());
        return MovimientoBilleteraResponse.de(movimiento, null, cripto.exploradorUrl());
    }

    @GetMapping("/retiros/limites")
    public LimitesRetiroResponse obtenerLimitesRetiro() {
        return retiroService.obtenerLimites();
    }

    @PostMapping("/retiros")
    @ResponseStatus(HttpStatus.CREATED)
    public MovimientoBilleteraResponse solicitarRetiro(@Valid @RequestBody SolicitarRetiroRequest request,
            @UsuarioActual Long usuarioId) {
        Movimiento movimiento = retiroService.solicitar(usuarioId, request.cuentaId(), request.monto());
        return MovimientoBilleteraResponse.de(movimiento, null, cripto.exploradorUrl());
    }
}

