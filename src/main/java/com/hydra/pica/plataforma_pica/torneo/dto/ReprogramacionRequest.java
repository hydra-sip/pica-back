package com.hydra.pica.plataforma_pica.torneo.dto;

import java.time.Instant;

/**
 * La reprogramación que pide el organizador (CR-01, PICA-391). Las validaciones las hace el servicio, todas juntas en un
 * solo 400. {@code fechaFin} es opcional, como en el torneo.
 *
 * @param rechazoHasta hasta cuándo los inscriptos pueden decir que la nueva fecha no les sirve
 * @param cierreInscripcion hasta cuándo se puede anotar gente en los lugares que se liberen; posterior al plazo
 */
public record ReprogramacionRequest(Instant fechaInicio, Instant fechaFin, Instant cierreInscripcion,
                                    Instant rechazoHasta, String motivo) {
}
