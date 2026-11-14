package com.hydra.pica.plataforma_pica.billetera.dto;

import java.math.BigDecimal;

import com.hydra.pica.plataforma_pica.billetera.domain.CargaCripto;

/**
 * Lo que el front necesita para pagar la carga en el contrato. {@code montoWei} va como texto porque pasa el rango
 * de los números que JavaScript representa con exactitud.
 */
public record CargaCriptoResponse(String referencia, String contrato, long chainId, String montoWei,
                                  BigDecimal montoPesos) {

    public static CargaCriptoResponse de(CargaCripto carga, String contrato, long chainId) {
        return new CargaCriptoResponse(carga.getReferencia(), contrato, chainId, carga.getMontoWei().toString(),
                carga.getMontoPesos());
    }
}
