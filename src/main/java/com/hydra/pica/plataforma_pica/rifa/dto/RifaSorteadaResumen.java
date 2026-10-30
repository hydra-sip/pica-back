package com.hydra.pica.plataforma_pica.rifa.dto;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;

/**
 * Fila del listado de rifas sorteadas para el portal de verificación (HU-504). {@code torneoNombre} es null si la
 * rifa no tiene torneo; {@code semillaObtenidaEn} es cuándo se sorteó de verdad.
 */
public record RifaSorteadaResumen(Long id, String titulo, String torneoNombre, Instant semillaObtenidaEn) {

    public static RifaSorteadaResumen de(Rifa rifa) {
        return new RifaSorteadaResumen(rifa.getId(), rifa.getTitulo(),
                rifa.getTorneo() == null ? null : rifa.getTorneo().getNombre(), rifa.getSemillaObtenidaEn());
    }
}
