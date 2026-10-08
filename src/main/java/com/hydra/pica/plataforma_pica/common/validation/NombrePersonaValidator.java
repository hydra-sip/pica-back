package com.hydra.pica.plataforma_pica.common.validation;

import java.text.Normalizer;
import java.util.regex.Pattern;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class NombrePersonaValidator implements ConstraintValidator<NombrePersona, String> {

    // \p{L}: cualquier letra Unicode, incluidas las acentuadas y la ñ. Es el mismo pattern del contrato.
    private static final Pattern NOMBRE = Pattern.compile("^\\p{L}+([ '\\-]\\p{L}+)*$");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        // NFC junta "e" + acento combinado en "é": algunos teclados mandan la forma separada
        return NOMBRE.matcher(Normalizer.normalize(value.strip(), Normalizer.Form.NFC)).matches();
    }
}
