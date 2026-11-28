package com.hydra.pica.plataforma_pica.reputacion.dto;

import java.time.Instant;

/** Premio de rifa sorteada ganado por un usuario, para exhibir en su perfil (HU-804). */
public record RifaGanada(
        Long rifaId,
        String rifaTitulo,
        Long torneoId,
        String torneoNombre,
        String torneoSlug,
        int puesto,
        Integer numeroGanador,
        Instant fecha) {
}
