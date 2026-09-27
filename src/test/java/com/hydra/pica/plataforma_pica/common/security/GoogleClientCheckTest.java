package com.hydra.pica.plataforma_pica.common.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class GoogleClientCheckTest {

    @Test
    @DisplayName("Fuera de dev, sin el cliente de Google no arranca y dice qué falta")
    void fueraDeDevSinClienteNoArranca() {
        MockEnvironment environment = new MockEnvironment().withProperty("GOOGLE_CLIENT_ID", "id");
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> new GoogleClientCheck(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GOOGLE_CLIENT_SECRET")
                .hasMessageNotContaining("GOOGLE_CLIENT_ID");
    }

    @Test
    @DisplayName("Fuera de dev, con las dos variables arranca")
    void fueraDeDevConClienteArranca() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("GOOGLE_CLIENT_ID", "id")
                .withProperty("GOOGLE_CLIENT_SECRET", "secret");
        environment.setActiveProfiles("prod");

        assertThatCode(() -> new GoogleClientCheck(environment)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("En dev arranca sin el cliente de Google")
    void enDevArrancaSinCliente() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        assertThatCode(() -> new GoogleClientCheck(environment)).doesNotThrowAnyException();
    }
}
