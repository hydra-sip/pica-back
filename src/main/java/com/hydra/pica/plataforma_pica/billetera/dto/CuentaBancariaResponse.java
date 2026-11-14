package com.hydra.pica.plataforma_pica.billetera.dto;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.billetera.domain.CuentaBancaria;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoValidacion;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoCuenta;

/**
 * Cuenta bancaria o virtual del usuario (HU-607, PICA-336).
 */
public record CuentaBancariaResponse(
        Long id,
        TipoCuenta tipo,
        String valor,
        String titular,
        String cuitCuil,
        EstadoValidacion estadoValidacion,
        Instant validadaEn,
        Instant creadoEn) {

    public static CuentaBancariaResponse de(CuentaBancaria cuenta) {
        return new CuentaBancariaResponse(
                cuenta.getId(),
                cuenta.getTipo(),
                cuenta.getValor(),
                cuenta.getTitular(),
                cuenta.getCuitCuil(),
                cuenta.getEstadoValidacion(),
                cuenta.getValidadaEn(),
                cuenta.getCreadoEn());
    }
}

