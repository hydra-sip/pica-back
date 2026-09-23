package com.hydra.pica.plataforma_pica.common.error;

import org.springframework.http.HttpStatus;

/**
 * 403 por una protección del negocio, no por falta de permiso: quien llama tiene el permiso, pero
 * ese recurso no se puede tocar así (el rol Super Usuario, el admin del sistema, ...). El código
 * dice cuál. La falta de permiso es otra cosa y sale como SIN_PERMISO desde {@code @PreAuthorize}.
 */
public class ProhibidoException extends ApiException {

    public ProhibidoException(CodigoError codigo, String detalle) {
        super(HttpStatus.FORBIDDEN, codigo, detalle);
    }
}
