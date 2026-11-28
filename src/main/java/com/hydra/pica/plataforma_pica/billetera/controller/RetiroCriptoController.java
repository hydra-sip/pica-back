package com.hydra.pica.plataforma_pica.billetera.controller;

import java.util.List;

import com.hydra.pica.plataforma_pica.billetera.domain.RetiroCripto;
import com.hydra.pica.plataforma_pica.billetera.dto.ConfirmarRetiroCriptoRequest;
import com.hydra.pica.plataforma_pica.billetera.dto.RetiroCriptoResponse;
import com.hydra.pica.plataforma_pica.billetera.dto.SolicitarRetiroCriptoRequest;
import com.hydra.pica.plataforma_pica.billetera.service.RetiroCriptoService;
import com.hydra.pica.plataforma_pica.common.config.CriptoConfig.CriptoProperties;
import com.hydra.pica.plataforma_pica.common.config.CriptoConfig.RetirosCriptoProperties;
import com.hydra.pica.plataforma_pica.common.security.UsuarioActual;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Retiro de saldo a la wallet del usuario autenticado (HU-610, PICA-455), con la pasarela PasarelaRetiros. */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@RequestMapping("/api/v1/billetera/retiros/cripto")
public class RetiroCriptoController {

    private final RetiroCriptoService retiroCriptoService;
    private final CriptoProperties cripto;
    private final RetirosCriptoProperties retiros;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RetiroCriptoResponse solicitar(@Valid @RequestBody SolicitarRetiroCriptoRequest request,
            @UsuarioActual Long usuarioId) {
        return respuesta(retiroCriptoService.solicitar(usuarioId, request.monto(), request.destino()));
    }

    /** Los permisos que el usuario todavía puede cobrar, para retomarlos si cerró la pestaña. */
    @GetMapping
    public List<RetiroCriptoResponse> pendientes(@UsuarioActual Long usuarioId) {
        return retiroCriptoService.pendientes(usuarioId).stream().map(this::respuesta).toList();
    }

    @PostMapping("/{referencia}/confirmar")
    public RetiroCriptoResponse confirmar(@PathVariable String referencia,
                                          @Valid @RequestBody ConfirmarRetiroCriptoRequest request,
                                          @UsuarioActual Long usuarioId) {
        return respuesta(retiroCriptoService.confirmar(usuarioId, referencia, request.hash()));
    }

    private RetiroCriptoResponse respuesta(RetiroCripto retiro) {
        return RetiroCriptoResponse.de(retiro, retiros.contrato(), cripto.chainId(), cripto.exploradorUrl());
    }
}
