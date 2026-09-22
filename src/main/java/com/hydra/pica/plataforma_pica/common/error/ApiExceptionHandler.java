package com.hydra.pica.plataforma_pica.common.error;

import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Traduce todas las excepciones a ProblemDetail (RFC 9457) con el campo {@code codigo},
 * como está definido en docs/api/openapi.yaml. Es el único lugar donde se arman errores:
 * los controllers y servicios solo lanzan excepciones.
 *
 * Los 401/403 que corta Spring Security antes de llegar al controller (sin token, token vencido)
 * no pasan por acá; los maneja el filtro JWT (PICA-117). Los que sí llegan son los de
 * {@code @PreAuthorize}, porque saltan adentro del método del controller.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ProblemDetail manejarApiException(ApiException ex) {
        log.debug("{} {}: {}", ex.getStatus().value(), ex.getCodigo(), ex.getMessage());
        return problema(ex.getStatus(), ex.getCodigo(), ex.getMessage(), null);
    }

    /** @PreAuthorize sin el permiso necesario. Sin esto caería en el 500 genérico de abajo. */
    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail manejarAccesoDenegado(AccessDeniedException ex) {
        return problema(HttpStatus.FORBIDDEN, CodigoError.SIN_PERMISO, "No tenés permiso para esta operación", null);
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail manejarNoAutenticado(AuthenticationException ex) {
        return problema(HttpStatus.UNAUTHORIZED, CodigoError.NO_AUTENTICADO, "Hace falta iniciar sesión", null);
    }

    /** Validación de parámetros sueltos (@Validated en el controller), no del body. */
    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail manejarConstraintViolation(ConstraintViolationException ex) {
        List<ErrorCampo> errores = ex.getConstraintViolations().stream()
                .map(v -> new ErrorCampo(
                        ultimoNodo(v.getPropertyPath().toString()),
                        ErrorCampo.Codigo.desdeAnotacion(
                                v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName()),
                        v.getMessage()))
                .toList();
        return problema(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay parámetros inválidos", errores);
    }

    /** Bean Validation del body (@Valid en el @RequestBody). Un ítem por campo que falló. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        List<ErrorCampo> errores = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ErrorCampo(
                        fe.getField(),
                        ErrorCampo.Codigo.desdeAnotacion(fe.getCode()),
                        fe.getDefaultMessage()))
                .toList();
        ProblemDetail pd = problema(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay campos inválidos", errores);
        return handleExceptionInternal(ex, pd, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** JSON mal formado o con un tipo que no cierra (por ejemplo, texto donde va una fecha). */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail pd = problema(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION,
                "El cuerpo del request no se pudo leer", null);
        return handleExceptionInternal(ex, pd, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** Falta un query param obligatorio, como el {@code token} de /auth/verificar. */
    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException ex,
                                                                          HttpHeaders headers,
                                                                          HttpStatusCode status,
                                                                          WebRequest request) {
        List<ErrorCampo> errores = List.of(new ErrorCampo(
                ex.getParameterName(), ErrorCampo.Codigo.REQUERIDO, "Es obligatorio"));
        ProblemDetail pd = problema(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay parámetros inválidos", errores);
        return handleExceptionInternal(ex, pd, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** Cualquier otra cosa es un bug nuestro: se loguea completa y al cliente le llega un 500 sin detalles. */
    @ExceptionHandler(Exception.class)
    ProblemDetail manejarInesperada(Exception ex) {
        log.error("Error no controlado", ex);
        return problema(HttpStatus.INTERNAL_SERVER_ERROR, CodigoError.ERROR_INTERNO, "Error interno", null);
    }

    private static ProblemDetail problema(HttpStatus status, CodigoError codigo, String detalle, List<ErrorCampo> errores) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detalle);
        pd.setProperty("codigo", codigo.name());
        if (errores != null && !errores.isEmpty()) {
            pd.setProperty("errores", errores);
        }
        return pd;
    }

    /** De "crear.request.nroDoc" se queda con "nroDoc". */
    private static String ultimoNodo(String propertyPath) {
        int punto = propertyPath.lastIndexOf('.');
        return punto < 0 ? propertyPath : propertyPath.substring(punto + 1);
    }
}
