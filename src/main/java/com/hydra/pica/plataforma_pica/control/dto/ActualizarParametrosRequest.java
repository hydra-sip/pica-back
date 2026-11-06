package com.hydra.pica.plataforma_pica.control.dto;

import java.math.BigDecimal;
import java.util.Map;

import jakarta.validation.constraints.NotEmpty;

/**
 * Los parámetros a cambiar, por clave ({@code {"GASTOS_ADMINISTRATIVOS": 12}}). Los que no vienen quedan como están.
 */
public record ActualizarParametrosRequest(@NotEmpty Map<String, BigDecimal> valores) {
}
