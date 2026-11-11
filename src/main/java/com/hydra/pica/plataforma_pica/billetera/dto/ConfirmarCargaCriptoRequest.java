package com.hydra.pica.plataforma_pica.billetera.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ConfirmarCargaCriptoRequest(
        @NotNull(message = "El hash de la transacción es obligatorio")
        @Pattern(regexp = "^0x[0-9a-fA-F]{64}$", message = "El hash tiene que ser 0x y 64 caracteres hexadecimales")
        String hash
) {}
