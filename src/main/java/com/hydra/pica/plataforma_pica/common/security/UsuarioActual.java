package com.hydra.pica.plataforma_pica.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Parámetro {@code Long} de un endpoint que recibe el id del usuario de la sesión.
 * Lo resuelve {@link UsuarioActualResolver}: si no hay usuario da 401, salvo con {@code requerido = false},
 * que deja {@code null}.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface UsuarioActual {

    boolean requerido() default true;
}
