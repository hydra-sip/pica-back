package com.hydra.pica.plataforma_pica.common.error;

import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Helpers para inspeccionar violaciones de restricciones únicas o de integridad de Hibernate/PostgreSQL.
 */
public final class ViolacionesIntegridad {

    private ViolacionesIntegridad() {
    }

    /** Devuelve el nombre de la constraint subyacente si la cadena de causas contiene una {@link ConstraintViolationException}. */
    public static Optional<String> nombreDeConstraint(DataIntegrityViolationException e) {
        Throwable causa = e;
        while (causa != null && !(causa instanceof ConstraintViolationException)) {
            causa = causa.getCause();
        }
        if (causa instanceof ConstraintViolationException cve) {
            return Optional.ofNullable(cve.getConstraintName());
        }
        return Optional.empty();
    }

    /** Indica si la excepción corresponde exactamente a {@code nombreEsperado}. */
    public static boolean esConstraint(DataIntegrityViolationException e, String nombreEsperado) {
        return nombreDeConstraint(e).filter(nombreEsperado::equals).isPresent();
    }

    /** Indica si la excepción coincide (sin distinguir mayúsculas) con {@code nombreEsperado} o aparece en su mensaje. */
    public static boolean esConstraintOMensaje(DataIntegrityViolationException e, String nombreEsperado) {
        if (nombreDeConstraint(e).filter(nombreEsperado::equalsIgnoreCase).isPresent()) {
            return true;
        }
        String msg = e.getMessage();
        return msg != null && msg.contains(nombreEsperado);
    }
}
