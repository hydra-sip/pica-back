package com.hydra.pica.plataforma_pica.inscripcion.dto;

import java.math.BigDecimal;

/** El % del premio del equipo que le toca a un integrante de la nómina (CR-05). */
public record RepartoIntegrante(Long usuarioId, BigDecimal pct) {
}
