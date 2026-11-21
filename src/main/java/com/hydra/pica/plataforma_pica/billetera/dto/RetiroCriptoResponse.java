package com.hydra.pica.plataforma_pica.billetera.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.hydra.pica.plataforma_pica.billetera.domain.EstadoRetiroCripto;
import com.hydra.pica.plataforma_pica.billetera.domain.RetiroCripto;

/**
 * Lo que el front necesita para cobrar el retiro en PasarelaRetiros: {@code cobrar(referencia, montoWei, vence,
 * firma)} desde la wallet {@code destino}, en {@code contrato} y {@code chainId}. {@code montoWei} va como texto porque
 * pasa el rango de los números que JavaScript representa con exactitud; {@code vence} es el timestamp en segundos
 * que se le pasa al contrato, y {@code venceEn} el mismo instante para mostrarlo.
 */
public record RetiroCriptoResponse(String referencia, String contrato, long chainId, String destino,
                                   BigDecimal montoPesos, String montoWei, long vence, Instant venceEn, String firma,
                                   EstadoRetiroCripto estado, String hash, String enlaceExplorador) {

    public static RetiroCriptoResponse de(RetiroCripto retiro, String contrato, long chainId, String exploradorUrl) {
        String enlace = retiro.getHash() == null ? null
                : exploradorUrl.replaceAll("/+$", "") + "/tx/" + retiro.getHash();
        return new RetiroCriptoResponse(retiro.getReferencia(), contrato, chainId, retiro.getDestino(),
                retiro.getMontoPesos(), retiro.getMontoWei().toString(), retiro.getVenceEn().getEpochSecond(),
                retiro.getVenceEn(), retiro.getFirma(), retiro.getEstado(), retiro.getHash(), enlace);
    }
}
