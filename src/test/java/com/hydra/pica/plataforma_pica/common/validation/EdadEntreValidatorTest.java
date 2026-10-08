package com.hydra.pica.plataforma_pica.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EdadEntreValidatorTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private record Pedido(@EdadEntre(min = EdadEntre.MINIMA_PERSONA, max = EdadEntre.MAXIMA_PERSONA)
                          LocalDate fechaNacimiento) {
    }

    private boolean esValida(LocalDate fecha) {
        return validator.validate(new Pedido(fecha)).isEmpty();
    }

    @Test
    @DisplayName("Acepta entre 18 y 120 años cumplidos, con los dos bordes incluidos")
    void edadesValidas() {
        LocalDate hoy = LocalDate.now();
        assertThat(esValida(hoy.minusYears(18))).as("cumple 18 hoy").isTrue();
        assertThat(esValida(hoy.minusYears(35))).isTrue();
        assertThat(esValida(hoy.minusYears(120))).as("cumple 120 hoy").isTrue();
    }

    @Test
    @DisplayName("Rechaza menores de 18 y mayores de 120")
    void edadesInvalidas() {
        LocalDate hoy = LocalDate.now();
        assertThat(esValida(hoy.minusYears(18).plusDays(1))).as("cumple 18 mañana").isFalse();
        assertThat(esValida(hoy.minusYears(5))).isFalse();
        assertThat(esValida(hoy)).as("nacido hoy").isFalse();
        assertThat(esValida(hoy.minusYears(121))).isFalse();
        assertThat(esValida(LocalDate.of(1850, 1, 1))).isFalse();
    }

    @Test
    @DisplayName("Nula o futura no opina: lo resuelven @NotNull y @PastOrPresent, así da un solo error")
    void nulaOFuturaNoOpina() {
        assertThat(esValida(null)).isTrue();
        assertThat(esValida(LocalDate.now().plusDays(1))).isTrue();
    }

    @Test
    @DisplayName("El mensaje dice el rango")
    void mensajeConElRango() {
        assertThat(validator.validate(new Pedido(LocalDate.now().minusYears(5))))
                .singleElement()
                .satisfies(error -> assertThat(error.getMessage()).isEqualTo("La edad tiene que estar entre 18 y 120 años"));
    }
}
