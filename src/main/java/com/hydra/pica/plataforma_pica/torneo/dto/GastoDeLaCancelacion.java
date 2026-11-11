package com.hydra.pica.plataforma_pica.torneo.dto;

import java.math.BigDecimal;

/** Un gasto que el organizador declaró al cancelar, como lo ve él (HU-605): sin el archivo. */
public record GastoDeLaCancelacion(String concepto, BigDecimal monto) {
}
