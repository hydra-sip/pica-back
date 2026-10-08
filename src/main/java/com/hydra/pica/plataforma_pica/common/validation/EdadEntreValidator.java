package com.hydra.pica.plataforma_pica.common.validation;

import java.time.LocalDate;
import java.time.Period;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class EdadEntreValidator implements ConstraintValidator<EdadEntre, LocalDate> {

    private int min;
    private int max;

    @Override
    public void initialize(EdadEntre anotacion) {
        this.min = anotacion.min();
        this.max = anotacion.max();
    }

    @Override
    public boolean isValid(LocalDate fechaNacimiento, ConstraintValidatorContext context) {
        LocalDate hoy = LocalDate.now();
        if (fechaNacimiento == null || fechaNacimiento.isAfter(hoy)) {
            return true;
        }
        int edad = Period.between(fechaNacimiento, hoy).getYears();
        return edad >= min && edad <= max;
    }
}
