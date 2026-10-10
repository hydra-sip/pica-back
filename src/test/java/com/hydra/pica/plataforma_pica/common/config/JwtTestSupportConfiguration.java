package com.hydra.pica.plataforma_pica.common.config;

import java.security.KeyPair;
import java.security.KeyPairGenerator;

import com.hydra.pica.plataforma_pica.common.security.JwtAuthenticationFilter;
import com.hydra.pica.plataforma_pica.common.security.JwtService;
import com.hydra.pica.plataforma_pica.common.security.OAuth2LoginHandler;
import com.hydra.pica.plataforma_pica.common.security.VersionesDeSesion;
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
        return new JwtService(keyPair, SesionConfig.SesionProperties.porDefecto());
    }

    @Bean
    JwtAuthenticationFilter jwtAuthenticationFilter(JwtService jwtService) {
        // Los tests web autentican con @WithMockUser: ninguno llega a consultar la versión de sesión
        return new JwtAuthenticationFilter(jwtService, Mockito.mock(VersionesDeSesion.class));
    }

    @Bean
    OAuth2LoginHandler oAuth2LoginHandler() {
        return Mockito.mock(OAuth2LoginHandler.class);
    }
}
