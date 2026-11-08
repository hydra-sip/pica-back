package com.hydra.pica.plataforma_pica.billetera.service;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;

/** 409 FONDOS_EN_CUSTODIA: los fondos de un evento no se mueven hasta que termina, lo pida quien lo pida. */
public class FondosEnCustodiaException extends ConflictoException {

    public FondosEnCustodiaException(String detalle) {
        super(CodigoError.FONDOS_EN_CUSTODIA, detalle);
    }
}
