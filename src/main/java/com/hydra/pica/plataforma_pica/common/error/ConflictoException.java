package com.hydra.pica.plataforma_pica.common.error;

import org.springframework.http.HttpStatus;

/**
 * 409: una regla de negocio no se cumple (duplicados, estados que no permiten la operación, etc.).
 * El código dice cuál; el detalle es orientativo para quien está debuggeando.
 */
public class ConflictoException extends ApiException {

    public ConflictoException(CodigoError codigo, String detalle) {
        super(HttpStatus.CONFLICT, codigo, detalle);
    }
}
