package com.hydra.pica.plataforma_pica.common.error;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Saca el nombre de la constraint que hizo fallar un guardado, para que cada servicio traduzca su choque
 * (por ejemplo, contra un índice único) al 409 que corresponde.
 */
public final class NombreDeConstraint {

    private NombreDeConstraint() {
    }

    /** Recorre las causas hasta la de Hibernate. Devuelve {@code null} si no hay o si no trae nombre. */
    public static String de(DataIntegrityViolationException e) {
        Throwable causa = e;
        while (causa != null && !(causa instanceof ConstraintViolationException)) {
            causa = causa.getCause();
        }
        return causa == null ? null : ((ConstraintViolationException) causa).getConstraintName();
    }
}
