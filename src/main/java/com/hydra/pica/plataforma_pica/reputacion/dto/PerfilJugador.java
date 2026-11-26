package com.hydra.pica.plataforma_pica.reputacion.dto;

/**
 * Perfil público de un jugador (HU-801, T801-2): lo que se puede mostrar sin sesión. Nunca lleva documento, email ni
 * datos bancarios. {@code avatar} es siempre null hasta que la plataforma tenga avatares.
 */
public record PerfilJugador(
        Long id,
        String username,
        String nombreCompleto,
        String avatar,
        HistorialCompetitivo historial) {
}
