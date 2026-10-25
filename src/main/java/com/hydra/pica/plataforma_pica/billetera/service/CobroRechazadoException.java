package com.hydra.pica.plataforma_pica.billetera.service;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;

/** 409 COBRO_RECHAZADO: el pago no se aprobó y no se cobró nada. */
public class CobroRechazadoException extends ConflictoException {

    public CobroRechazadoException(String detalle) {
        super(CodigoError.COBRO_RECHAZADO, detalle);
    }
}
