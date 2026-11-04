package com.hydra.pica.plataforma_pica.partido.dto;

/** @param nombre el del equipo o, si se inscribe un jugador, su nombre y apellido */
public record LadoBracket(Long inscripcionId, String nombre, Integer puntaje, Integer penales, Boolean ganador) {
}
