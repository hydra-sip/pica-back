package com.hydra.pica.plataforma_pica.partido.dto;

import java.time.Instant;

/** La fecha y hora del partido (HU-308); lo que la compara con hoy y con el torneo lo valida el servicio. */
public record ProgramacionRequest(Instant fechaProgramada) {
}
