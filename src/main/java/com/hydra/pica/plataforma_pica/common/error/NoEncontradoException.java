package com.hydra.pica.plataforma_pica.common.error;

import org.springframework.http.HttpStatus;

/**
 * 404: el recurso pedido por id no existe (o está fuera del alcance del que pregunta).
 */
public class NoEncontradoException extends ApiException {

    public NoEncontradoException(CodigoError codigo, String detalle) {
        super(HttpStatus.NOT_FOUND, codigo, detalle);
    }
}
