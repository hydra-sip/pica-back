package com.hydra.pica.plataforma_pica.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NombrePersonaValidatorTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private record Pedido(@NombrePersona String nombre) {
    }

    private boolean esValido(String nombre) {
        return validator.validate(new Pedido(nombre)).isEmpty();
    }

    @Test
    @DisplayName("Acepta letras con tildes y ñ, y palabras separadas por espacio, apóstrofo o guion")
    void nombresValidos() {
        assertThat(esValido("Juan")).isTrue();
        assertThat(esValido("María José")).isTrue();
        assertThat(esValido("Muñoz")).isTrue();
        assertThat(esValido("O'Connor")).isTrue();
        assertThat(esValido("Pérez-Gómez")).isTrue();
        assertThat(esValido("Ñandú Güemes")).isTrue();
        assertThat(esValido("  Juan  ")).as("los espacios de los costados se ignoran").isTrue();
        // "é" escrita como "e" + acento combinado (U+0301): algunos teclados la mandan así
        assertThat(esValido("José")).isTrue();
    }

    @Test
    @DisplayName("Rechaza números, símbolos y separadores sueltos o repetidos")
    void nombresInvalidos() {
        assertThat(esValido("Juan2")).isFalse();
        assertThat(esValido("J0sé")).isFalse();
        assertThat(esValido("Juan.")).isFalse();
        assertThat(esValido("Juan@Pérez")).isFalse();
        assertThat(esValido("-Juan")).isFalse();
        assertThat(esValido("Juan-")).isFalse();
        assertThat(esValido("Juan  Carlos")).as("dos espacios seguidos").isFalse();
        assertThat(esValido("Pérez--Gómez")).isFalse();
    }

    @Test
    @DisplayName("Nulo o en blanco no opina: eso lo resuelve @NotBlank, así da un solo error")
    void vacioNoOpina() {
        assertThat(esValido(null)).isTrue();
        assertThat(esValido("")).isTrue();
        assertThat(esValido("   ")).isTrue();
    }
}
