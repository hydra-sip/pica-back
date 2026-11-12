package com.hydra.pica.plataforma_pica.billetera.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ValidadorCbuCvuTest {

    @Test
    @DisplayName("CBU y CVU válidos pasan el algoritmo de ponderación del BCRA")
    void cbuYCvuValidos() {
        assertThat(ValidadorCbuCvu.esValido("2850590940090418135201")).isTrue();
        assertThat(ValidadorCbuCvu.esValido("0000003100000001234565")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2850590840090418135201", // dígito verificador 1 erróneo (8 en vez de 9)
            "2850590940090418135202", // dígito verificador 2 erróneo (2 en vez de 1)
            "285059094009041813520",  // 21 caracteres
            "28505909400904181352011", // 23 caracteres
            "285059094009041813520A", // letras
            "",
            "   "
    })
    @DisplayName("CBU/CVU inválidos o mal formados son rechazados")
    void cbuInvalidoRechazado(String cbu) {
        assertThat(ValidadorCbuCvu.esValido(cbu)).isFalse();
    }

    @Test
    @DisplayName("CBU nulo devuelve false")
    void cbuNuloDevuelveFalse() {
        assertThat(ValidadorCbuCvu.esValido(null)).isFalse();
    }
}

