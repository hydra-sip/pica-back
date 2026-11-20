package com.hydra.pica.plataforma_pica.reputacion.dto;

/**
 * El puntaje de reputación de un jugador (HU-805): el promedio de las calificaciones que le pusieron los organizadores
 * y cuántas son. Sin calificaciones, el promedio es null.
 */
public record Reputacion(Double promedio, long cantidad) {

    public static final Reputacion SIN_CALIFICACIONES = new Reputacion(null, 0);
}
