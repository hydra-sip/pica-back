package com.hydra.pica.plataforma_pica.billetera.dto;

import java.math.BigDecimal;

import com.hydra.pica.plataforma_pica.billetera.domain.Billetera;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoBilletera;

/**
 * Vista de la billetera propia (HU-602, T602-1).
 */
public record BilleteraResponse(
        BigDecimal saldoDisponible,
        BigDecimal saldoRetenido,
        BigDecimal gananciaAcumulada,
        String moneda,
        EstadoBilletera estado) {

    public static BilleteraResponse de(Billetera billetera) {
        return new BilleteraResponse(
                billetera.getSaldoDisponible(),
                billetera.getSaldoRetenido(),
                billetera.getGananciaAcumulada(),
                billetera.getMoneda(),
                billetera.getEstado());
    }
}

