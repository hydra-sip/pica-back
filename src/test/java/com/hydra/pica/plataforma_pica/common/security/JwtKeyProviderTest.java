package com.hydra.pica.plataforma_pica.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class JwtKeyProviderTest {

    @Test
    @DisplayName("En perfil dev sin claves genera un par efímero y arranca")
    void enDevSinClavesGeneraParEfimero() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        JwtKeyProvider provider = new JwtKeyProvider(environment);
        KeyPair keyPair = provider.jwtKeyPair();

        assertThat(keyPair).isNotNull();
        assertThat(keyPair.getPublic()).isNotNull();
        assertThat(keyPair.getPrivate()).isNotNull();
    }

    @Test
    @DisplayName("Fuera de dev, si faltan las dos claves lanza excepción y no arranca")
    void fueraDeDevSinClavesFalla() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        JwtKeyProvider provider = new JwtKeyProvider(environment);

        assertThatThrownBy(provider::jwtKeyPair)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PRIVATE_KEY")
                .hasMessageContaining("JWT_PUBLIC_KEY")
                .hasMessageContaining("perfil dev");
    }

    @Test
    @DisplayName("Fuera de dev, si falta solo JWT_PUBLIC_KEY lanza excepción")
    void fueraDeDevSinClavePublicaFalla() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("JWT_PRIVATE_KEY", "algo");
        environment.setActiveProfiles("prod");

        JwtKeyProvider provider = new JwtKeyProvider(environment);

        assertThatThrownBy(provider::jwtKeyPair)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PUBLIC_KEY")
                .hasMessageNotContaining("JWT_PRIVATE_KEY");
    }

    @Test
    @DisplayName("Fuera de dev, si falta solo JWT_PRIVATE_KEY lanza excepción")
    void fueraDeDevSinClavePrivadaFalla() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("JWT_PUBLIC_KEY", "algo");
        environment.setActiveProfiles("prod");

        JwtKeyProvider provider = new JwtKeyProvider(environment);

        assertThatThrownBy(provider::jwtKeyPair)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PRIVATE_KEY")
                .hasMessageNotContaining("JWT_PUBLIC_KEY");
    }

    @Test
    @DisplayName("Fuera de dev con claves válidas carga el par correctamente")
    void fueraDeDevConClavesValidasCargaPar() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();

        String privatePem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
        String publicPem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(pair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";

        MockEnvironment environment = new MockEnvironment()
                .withProperty("JWT_PRIVATE_KEY", privatePem)
                .withProperty("JWT_PUBLIC_KEY", publicPem);
        environment.setActiveProfiles("prod");

        JwtKeyProvider provider = new JwtKeyProvider(environment);
        KeyPair loaded = provider.jwtKeyPair();

        assertThat(loaded).isNotNull();
        assertThat(loaded.getPublic().getEncoded()).isEqualTo(pair.getPublic().getEncoded());
        assertThat(loaded.getPrivate().getEncoded()).isEqualTo(pair.getPrivate().getEncoded());
    }
}
