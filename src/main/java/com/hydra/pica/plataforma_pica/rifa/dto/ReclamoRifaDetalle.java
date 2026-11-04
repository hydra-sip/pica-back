package com.hydra.pica.plataforma_pica.rifa.dto;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.rifa.domain.EstadoReclamo;

/**
 * Detalle de un reclamo sobre el resultado de una rifa (HU-506, T506-1).
 */
public record ReclamoRifaDetalle(
        Long id,
        Long rifaId,
        Long usuarioId,
        String motivo,
        EstadoReclamo estado,
        String resolucion,
        Instant resueltoEn,
        String resueltoPor,
        Instant creadoEn
) {
}
