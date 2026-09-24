package com.hydra.pica.plataforma_pica.user.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * El número de documento tiene que ser coherente con el tipo: DNI, LC y LE llevan solo dígitos;
 * PASAPORTE y CI son alfanuméricos y llevan al menos un dígito. Va sobre el request entero (que
 * implementa {@link ConDocumento}) porque depende de dos campos; el error se informa en {@code nroDoc}.
 * No reemplaza a {@code @Size} ni a {@code @Pattern}: si falta alguno de los dos campos, no opina.
 */
@Documented
@Constraint(validatedBy = DocumentoValidoValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DocumentoValido {

    String message() default "El número de documento no es válido para el tipo indicado";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
