package com.hydra.pica.plataforma_pica.billetera.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Solicitud de retiro de saldo disponible hacia una cuenta bancaria o virtual (HU-607, PICA-337).
 */
public record SolicitarRetiroRequest(
        @NotNull(message = "La cuenta bancaria destino es obligatoria")
        Long cuentaId,

        @NotNull(message = "El monto a retirar es obligatorio")
        @Positive(message = "El monto a retirar debe ser mayor a cero")
        BigDecimal monto) {
}

