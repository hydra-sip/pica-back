package com.hydra.pica.plataforma_pica.common.error;

import org.springframework.http.HttpStatus;

/**
 * 429 DEMASIADOS_INTENTOS (RNF-SEG-06): con {@code reintentarEnSegundos} en el cuerpo y el header {@code Retry-After}.
 */
public class DemasiadosIntentosException extends ApiException {

    private final long segundos;

    public DemasiadosIntentosException(long segundos, String detalle) {
        super(HttpStatus.TOO_MANY_REQUESTS, CodigoError.DEMASIADOS_INTENTOS, detalle);
        this.segundos = segundos;
        con("reintentarEnSegundos", segundos);
    }

    public long getSegundos() {
        return segundos;
    }
}
