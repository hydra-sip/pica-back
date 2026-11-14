package com.hydra.pica.plataforma_pica.billetera.dto;

import com.hydra.pica.plataforma_pica.billetera.domain.TipoCuenta;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Solicitud de alta de cuenta bancaria o virtual (HU-607, PICA-336).
 */
public record CrearCuentaBancariaRequest(
        @NotNull(message = "El tipo de cuenta es obligatorio")
        TipoCuenta tipo,

        @NotBlank(message = "El CBU, CVU o alias es obligatorio")
        String valor,

        @NotBlank(message = "El titular es obligatorio")
        String titular,

        @NotBlank(message = "El CUIT o CUIL es obligatorio")
        String cuitCuil) {
}

