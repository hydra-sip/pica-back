package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigDecimal;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;

/** 409 SALDO_INSUFICIENTE: el disponible no alcanza y no se cobró nada. {@code faltante} es lo que hay que cargar. */
public class SaldoInsuficienteException extends CobroRechazadoException {

    public SaldoInsuficienteException(BigDecimal faltante) {
        super(CodigoError.SALDO_INSUFICIENTE, "El saldo disponible no alcanza: faltan " + faltante);
        con("faltante", faltante);
    }
}
