package com.hydra.pica.plataforma_pica.common.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Fecha de nacimiento con una edad, cumplida hoy, entre {@code min} y {@code max} años (CE2-3).
 * Nula o futura no la controla: la obligatoriedad es de {@code @NotNull} y una fecha futura la marca
 * {@code @PastOrPresent} (FECHA_FUTURA), así cada caso da un solo error.
 */
@Documented
@Constraint(validatedBy = EdadEntreValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface EdadEntre {

    /** Edades aceptadas para cualquier persona, la cargue ella misma o un administrador (CE2-3). */
    int MINIMA_PERSONA = 18;
    int MAXIMA_PERSONA = 120;

    int min();

    int max();

    String message() default "La edad tiene que estar entre {min} y {max} años";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
