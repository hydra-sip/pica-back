package com.hydra.pica.plataforma_pica.reputacion.dto;

import java.time.Instant;

/**
 * Perfil público de un jugador (HU-801, T801-2): lo que se puede mostrar sin sesión. Nunca lleva documento, email ni
 * datos bancarios. {@code avatar} es siempre null hasta que la plataforma tenga avatares.
 */
public record PerfilJugador(
        Long id,
        String username,
        String nombreCompleto,
        String avatar,
        HistorialCompetitivo historial,
        IndiceFairPlay fairPlay) {

    /**
     * El índice de fair play actual, sin el historial de movimientos: las sanciones y su gravedad quedan detrás de
     * sesión. {@code actualizadoEn} es null si nunca se movió (tiene el inicial).
     */
    public record IndiceFairPlay(int valor, Instant actualizadoEn) {
    }
}
