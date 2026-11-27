package com.hydra.pica.plataforma_pica.reputacion.dto;

import java.util.List;

/** Rifas ganadas e insignias obtenidas por un jugador para exhibir en su perfil (HU-804, PICA-425). */
public record LogrosJugador(
        Long usuarioId,
        List<InsigniaObtenida> insignias,
        List<RifaGanada> rifasGanadas) {
}
