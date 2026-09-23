package com.hydra.pica.plataforma_pica.common.validation;

import java.util.regex.Pattern;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PasswordValidaValidator implements ConstraintValidator<PasswordValida, String> {

    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[A-Z])(?=.*\\d).{8,}$");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || PASSWORD_PATTERN.matcher(value).matches();
    }
}
