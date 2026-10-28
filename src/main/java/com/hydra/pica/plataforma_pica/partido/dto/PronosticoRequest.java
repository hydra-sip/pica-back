package com.hydra.pica.plataforma_pica.partido.dto;

/**
 * Petición para registrar o actualizar el pronóstico de un partido (HU-306, T306-1).
 * La elección puede ser "1" (LOCAL), "X" (EMPATE) o "2" (VISITANTE).
 */
public record PronosticoRequest(
        String eleccion) {
}
