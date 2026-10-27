package com.hydra.pica.plataforma_pica.partido.dto;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.partido.domain.Decision;
import com.hydra.pica.plataforma_pica.partido.domain.Lado;

/**
 * Un registro del historial del partido, sacado de la auditoría (HU-303).
 *
 * @param evento   PROGRAMADO, CHECKIN, CARGADO, ACEPTADO, OBJETADO, CONFIRMADO_POR_PLAZO o LAUDO
 * @param usuario  nombre y apellido; null si lo hizo el sistema
 * @param decision solo en el laudo
 */
public record EventoPartido(
        String evento,
        String usuario,
        Lado lado,
        String equipo,
        Instant fecha,
        Decision decision) {
}
