package com.hydra.pica.plataforma_pica.reputacion.event;

import com.hydra.pica.plataforma_pica.reputacion.domain.Gravedad;

/** Se aplicó una sanción (HU-803): la escuchan el aviso al jugador y el índice de fair play (HU-802). */
public record SancionAplicada(Long sancionId, Long torneoId, Long usuarioId, Gravedad gravedad) {
}
