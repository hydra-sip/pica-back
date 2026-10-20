package com.hydra.pica.plataforma_pica.common.error;

import java.util.LinkedHashMap;
import java.util.Map;

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
    private final Map<String, Object> propiedades = new LinkedHashMap<>();

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

    /**
     * Agrega un campo al ProblemDetail además de {@code codigo}, para datos que el front necesita
     * leer sin parsear {@code detail} (por ejemplo, qué permisos no existen). Cada campo nuevo va
     * también en el schema ProblemDetail del contrato.
     */
    public ApiException con(String propiedad, Object valor) {
        propiedades.put(propiedad, valor);
        return this;
    }

    public Map<String, Object> getPropiedades() {
        return propiedades;
    }
}
