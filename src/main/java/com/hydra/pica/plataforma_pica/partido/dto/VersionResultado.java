package com.hydra.pica.plataforma_pica.partido.dto;

import java.time.Instant;
import java.util.List;

import com.hydra.pica.plataforma_pica.partido.domain.Lado;

/**
 * El resultado que sostiene una de las partes de un partido objetado (HU-303): la que lo cargó o la que lo objetó.
 *
 * @param equipo     el del equipo o, si se inscribe un jugador, su nombre y apellido
 * @param fecha      cuándo se cargó o se objetó
 * @param evidencias la que subió ese lado; null en el listado del torneo
 */
public record VersionResultado(
        Lado lado,
        String equipo,
        int puntajeLocal,
        int puntajeVisitante,
        Integer penalesLocal,
        Integer penalesVisitante,
        Instant fecha,
        List<EvidenciaDetalle> evidencias) {
}
