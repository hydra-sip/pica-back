package com.hydra.pica.plataforma_pica.torneo.dto;

/**
 * La ventana de check-in del torneo (HU-308): abre {@code minAntes} minutos antes de cada cruce y dura
 * {@code duracion} minutos. Lo que compara un valor con el otro lo valida {@code TorneoService}.
 */
public record CheckinTorneoRequest(Integer minAntes, Integer duracion) {
}
