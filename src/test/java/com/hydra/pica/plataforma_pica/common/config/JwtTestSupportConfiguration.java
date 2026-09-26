package com.hydra.pica.plataforma_pica.common.config;

import java.security.KeyPair;
import java.security.KeyPairGenerator;

import com.hydra.pica.plataforma_pica.common.security.JwtAuthenticationFilter;
import com.hydra.pica.plataforma_pica.common.security.JwtService;
import com.hydra.pica.plataforma_pica.common.security.OAuth2LoginSuccessHandler;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

@TestConfiguration
public class JwtTestSupportConfiguration {

    @Bean
    KeyPair jwtTestKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    @Bean
    JwtService jwtService(KeyPair keyPair) {
        return new JwtService(keyPair);
    }

    @Bean
    JwtAuthenticationFilter jwtAuthenticationFilter(JwtService jwtService) {
        return new JwtAuthenticationFilter(jwtService);
    }

    @Bean
    OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler() {
        return Mockito.mock(OAuth2LoginSuccessHandler.class);
    }
}
