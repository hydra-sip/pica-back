package com.hydra.pica.plataforma_pica.billetera.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/** Retiro de saldo a la wallet (HU-610, PICA-455): el monto en pesos y la wallet que lo va a cobrar. */
public record SolicitarRetiroCriptoRequest(
        @NotNull(message = "El monto a retirar es obligatorio")
        @Positive(message = "El monto a retirar debe ser mayor a cero")
        BigDecimal monto,

        @NotNull(message = "La wallet es obligatoria")
        @Pattern(regexp = "^0x(?!0{40})[0-9a-fA-F]{40}$",
                message = "La wallet tiene que ser una dirección 0x de 40 caracteres hexadecimales")
        String destino) {
}
