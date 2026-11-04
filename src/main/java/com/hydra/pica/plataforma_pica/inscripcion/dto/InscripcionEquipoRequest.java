package com.hydra.pica.plataforma_pica.inscripcion.dto;

import java.util.List;

import jakarta.validation.constraints.Size;

/**
 * Cuerpo del borrador de un torneo por equipos: un equipo del capitán ({@code equipoId}) o el nombre de uno
 * nuevo ({@code nombreEquipoNuevo}), exactamente uno de los dos, y la nómina de ese torneo como ids de
 * usuario. El capitán entra siempre en la nómina, figure o no en la lista.
 */
public record InscripcionEquipoRequest(
        Long equipoId,
        @Size(max = 100) String nombreEquipoNuevo,
        List<Long> nomina) {
}
