package com.hydra.pica.plataforma_pica.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OAuthCodeStoreTest {

    private OAuthCodeStore store;

    @BeforeEach
    void setUp() {
        store = new OAuthCodeStore();
    }

    @Test
    @DisplayName("Genera código y lo consume exitosamente")
    void generarYConsumirCodigo() {
        String code = store.generarCodigo(42L);

        assertThat(code).isNotBlank();
        Long usuarioId = store.consumirCodigo(code);

        assertThat(usuarioId).isEqualTo(42L);
    }

    @Test
    @DisplayName("El código es de un solo uso (el segundo intento falla)")
    void codigoEsDeUnSoloUso() {
        String code = store.generarCodigo(42L);

        assertThat(store.consumirCodigo(code)).isEqualTo(42L);
        assertThat(store.consumirCodigo(code)).isNull();
    }

    @Test
    @DisplayName("Código inexistente o nulo devuelve null")
    void codigoInexistenteODevuelveNull() {
        assertThat(store.consumirCodigo(null)).isNull();
        assertThat(store.consumirCodigo("   ")).isNull();
        assertThat(store.consumirCodigo("codigo-fantasma")).isNull();
    }
}
