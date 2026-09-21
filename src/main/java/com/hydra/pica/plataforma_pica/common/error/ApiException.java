package com.hydra.pica.plataforma_pica.common.error;

import org.springframework.http.HttpStatus;

/**
 * Base de las excepciones que la API traduce a un ProblemDetail con {@code codigo}.
 * Los servicios lanzan alguna de sus subclases ({@link ConflictoException},
 * {@link NoEncontradoException}, ...) y {@link ApiExceptionHandler} arma la respuesta.
 * Ningún controller debería devolver errores a mano.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final CodigoError codigo;

    public ApiException(HttpStatus status, CodigoError codigo, String detalle) {
        super(detalle);
        this.status = status;
        this.codigo = codigo;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public CodigoError getCodigo() {
        return codigo;
    }
}
