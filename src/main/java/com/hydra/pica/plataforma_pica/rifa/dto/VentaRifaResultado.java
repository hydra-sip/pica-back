package com.hydra.pica.plataforma_pica.rifa.dto;

import java.math.BigDecimal;
import java.util.List;

public record VentaRifaResultado(
        Long ticketId,
        String ticketCodigo,
        List<Integer> numeros,
        BigDecimal montoTotal,
        BigDecimal pozoRifa,
        BigDecimal pozoTorneo
) {
}
