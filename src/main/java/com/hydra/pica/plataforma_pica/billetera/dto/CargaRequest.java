package com.hydra.pica.plataforma_pica.billetera.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

public record CargaRequest(
        @NotNull(message = "El monto es obligatorio")
        @DecimalMin(value = "0.01", message = "El monto debe ser mayor a cero")
        @DecimalMax(value = "1000000.00", message = "El monto no puede superar $1.000.000,00")
        @Digits(integer = 7, fraction = 2, message = "El monto debe tener como máximo 2 decimales")
        BigDecimal monto
) {}
