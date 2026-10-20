package com.hydra.pica.plataforma_pica.common.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Nombres y apellidos de una persona (CE2-3): letras (con tildes y ñ), y palabras separadas por un
 * espacio, apóstrofo o guion ("María José", "O'Connor", "Pérez-Gómez"). Sin números ni símbolos.
 * Vacío o nulo no lo controla: eso es de {@code @NotBlank}, así un campo vacío da un solo error.
 */
@Documented
@Constraint(validatedBy = NombrePersonaValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface NombrePersona {

    String message() default "Solo letras, espacios, apóstrofo y guion";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
