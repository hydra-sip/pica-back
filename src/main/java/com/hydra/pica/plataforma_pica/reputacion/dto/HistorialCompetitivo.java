package com.hydra.pica.plataforma_pica.reputacion.dto;

/** Lo que jugó un usuario en la plataforma (HU-801): torneos terminados, partidos ganados y perdidos, y podios. */
public record HistorialCompetitivo(int torneosJugados, int victorias, int derrotas, int podios) {
}
