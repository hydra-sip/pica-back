package com.hydra.pica.plataforma_pica.partido.dto;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.domain.Lado;

/**
 * Un partido con lo que hace falta para cargar, aceptar, objetar o laudar su resultado (HU-301 a HU-304).
 *
 * @param torneoSlug      el del enlace de la ficha pública
 * @param nombreRonda     "Final", "Semifinal"... como en el bracket
 * @param cargadoPor      el lado que cargó el resultado; el otro es el que lo acepta
 * @param objecionVenceEn hasta cuándo el rival puede objetar; después se confirma solo
 * @param miLado          el lado del que es capitán quien consulta, si juega el partido
 * @param objecion        null si nadie lo objetó (HU-302)
 * @param puedoResolver   si quien consulta puede laudar la objeción pendiente: el organizador o un árbitro con permiso
 */
public record PartidoDetalle(
        Long id,
        Long torneoId,
        String torneo,
        String torneoSlug,
        int ronda,
        String nombreRonda,
        int posicion,
        EstadoPartido estado,
        boolean bye,
        LadoBracket local,
        LadoBracket visitante,
        String cancha,
        Instant fechaProgramada,
        String arbitro,
        Lado cargadoPor,
        Instant resultadoCargadoEn,
        Instant objecionVenceEn,
        Lado miLado,
        ObjecionDetalle objecion,
        boolean puedoResolver) {
}
