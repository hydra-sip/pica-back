package com.hydra.pica.plataforma_pica.common.validation;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PasswordValidaValidator implements ConstraintValidator<PasswordValida, String> {

    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[A-Z])(?=.*\\d).{8,}$");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null
                || (value.getBytes(StandardCharsets.UTF_8).length <= 72
                        && PASSWORD_PATTERN.matcher(value).matches());
    }
}
