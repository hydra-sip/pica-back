package com.hydra.pica.plataforma_pica.partido.dto;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;

/**
 * Fila de partido para el listado de pronósticos (HU-306, T306-1).
 *
 * @param partidoId       identificador del partido
 * @param torneoId        identificador del torneo
 * @param torneoNombre    nombre del torneo
 * @param ronda           número de ronda (1, 2...)
 * @param nombreRonda     nombre descriptivo ("Final", "Semifinal", etc.)
 * @param posicion        posición en la ronda
 * @param estado          estado actual del partido
 * @param fechaProgramada fecha y hora programada del partido
 * @param cierraEn        hasta cuándo se puede pronosticar (fechaProgramada)
 * @param abierto         true si la ventana para cargar/modificar pronóstico está abierta
 * @param local           lado local
 * @param visitante       lado visitante
 * @param miPronostico    pronóstico del usuario actual si existe; null si no pronosticó
 */
public record PartidoPronostico(
        Long partidoId,
        Long torneoId,
        String torneoNombre,
        int ronda,
        String nombreRonda,
        int posicion,
        EstadoPartido estado,
        Instant fechaProgramada,
        Instant cierraEn,
        boolean abierto,
        LadoBracket local,
        LadoBracket visitante,
        MiPronostico miPronostico) {
}
